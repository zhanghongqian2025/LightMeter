#!/usr/bin/env bash
# M4-macOS：打 .app 与 .pkg，并在打包后**真的把它跑起来**验证。
#
# 为什么不直接敲 jpackage 命令：
#   1. 模块集如果散落在命令与文档里，改一处忘一处，下一个产物就悄悄不对了；
#      这里用单一常量 MODULES 定义，文档与脚本共用一份口径。
#   2. 打包工具「成功退出」不等于「能用」。本脚本末尾会真的启动 .app、
#      真打一次 HTTP、真的脱敏一个文件——理由与 e2e 相同：工具报错不可信，
#      只有产品的外部行为可信。
#
# 用法：
#   ./tools/package-macos.sh            # 打 .app + .pkg + .dmg
#   SKIP_PKG=1 ./tools/package-macos.sh # 只打 .app
#   SKIP_DMG=1 ./tools/package-macos.sh # 打 .app + .pkg，不打 .dmg
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/tools/shell-lib.sh"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
# 公共参数。**离线开关不写在这里** —— 它要能被「按需」开关，见第 1 步。
MVN_ARGS=(-B -Dstyle.color=never)
# 可选：镜像 settings。国内网络慢时用 MAVEN_SETTINGS 指向一份带镜像的 settings.xml
#   （本机实测：central 17 KB/s，aliyun 镜像 346 KB/s，差 20 倍）。
# ⚠ 预热段与离线段**必须用同一份** settings：maven 会在 _remote.repositories 里
#   记下「构件是从哪个仓库 id 下载的」，离线段换了 settings（仓库 id 集合不同），
#   会以「仓库不完整」的**误导性**错误失败（实测：预热带 -s、离线不带 -s → RC=1）。
if [ -n "${MAVEN_SETTINGS:-}" ]; then
    MVN_ARGS+=(-s "$MAVEN_SETTINGS")
fi

# ---- 打包身份：唯一真源是 tools/package-identity.properties ----
#
# 【为什么不再在本文件里写死这些值】
#   这里原来有一份，tools/package-windows.ps1 里还有一份 → **同一事实两处定义**，
#   改一处忘一处**不会红**，只会让两个平台打出版本号不一样的产物。
#   → 抽到跨端真源，两个脚本各读一次（先例：tools/size-budget.properties）。
#   模块集「为什么是这五个」的实测理由也搬到了真源文件旁边 ——
#   理由写在两个脚本里各一份，同样会漂移。
#
# 【fail-closed：取不到就退出，不让它变成空字符串一路带着走】
#   空的 APP_VERSION 会打出一个「没有版本号」的包，而且**不报错**。
IDENTITY="${IDENTITY:-$ROOT/tools/package-identity.properties}"
if [ ! -f "$IDENTITY" ]; then
  echo "X 找不到打包身份真源：$IDENTITY"
  exit 1
fi
idprop() { sed -n "s/^$1=//p" "$IDENTITY" | head -1; }
# 用 printf -v 写进调用方给的变量名：这样取不到时**函数返回非 0**，
# 调用处能 exit 1。
#   （不能把 exit 写进 $(...) 里：命令替换跑在子 shell，exit 只结束子 shell，
#     主脚本会带着一个**空值**继续往下跑 —— 那正是最坏的 fail-open。）
read_prop() { # $1=键（sed 正则） $2=键名（给人看的） $3=目标变量名
  local v
  v="$(idprop "$1")"
  if [ -z "$v" ]; then
    echo "X 打包身份真源里没有 $2（或它是空的）：$IDENTITY" >&2
    return 1
  fi
  printf -v "$3" '%s' "$v"
}
read_prop 'app\.name' app.name APP_NAME || exit 1
read_prop 'app\.version' app.version APP_VERSION || exit 1
read_prop 'main\.class' main.class MAIN_CLASS || exit 1
read_prop 'jar\.name' jar.name JAR_NAME || exit 1
read_prop 'jlink\.modules' jlink.modules MODULES || exit 1
read_prop 'jvm\.args' jvm.args JVM_ARGS || exit 1
read_prop 'macos\.minimum\.version' macos.minimum.version MACOS_MIN_VERSION || exit 1

# jpackage 21 的内置 Info-lite.plist.template 把最低系统写死为 10.11，
# 但本包的 ONNX Runtime arm64 dylib 实际要求 13.3。用资源模板覆盖，并在
# target 下把唯一真源里的版本代入。jpackage 在 --resource-dir 里按它的
# publicName 查找这个资源，实际文件名必须是 Info.plist；app-image 与 pkg
# 必须共用同一份模板。
RESOURCE_TEMPLATE="$ROOT/tools/macos-resources/Info-lite.plist.template"
RESOURCE_STAGE="$ROOT/target/jpackage-resources"
[ -f "$RESOURCE_TEMPLATE" ] || { echo "X 缺少 jpackage Info.plist 模板：$RESOURCE_TEMPLATE" >&2; exit 1; }
rm_rf "$RESOURCE_STAGE"
mkdir -p "$RESOURCE_STAGE"
sed "s/DEPLOY_MINIMUM_SYSTEM_VERSION/$MACOS_MIN_VERSION/g" \
  "$RESOURCE_TEMPLATE" > "$RESOURCE_STAGE/Info.plist"

# 模型包目录：默认为空表示核心包不带模型（方案 B：AI 包独立分发）。
# 用 --ner-model 显式指定，或塞进 ~/.litemask/models/ner 都能启用。
MODEL_DIR="${MODEL_DIR:-}"

# 【2026-10-25 新增】**随程序分发的已签名模型包**所在目录。
#
# 【它与 MODEL_DIR 有什么不同 —— 这是两件事，别混】
#   MODEL_DIR        ：**解开**的模型目录（manifest.json + ner.onnx + vocab.txt），
#                      程序直接去那儿读，不验签。
#   MODEL_PACKAGE_DIR：**没解开**的模型包（一个 .zip + 它的 .sig），
#                      程序在**首次启动时**自己验签、原子安装到用户数据目录。
#
# 【为什么产品要走后者】
#   普通用户不该被要求「装完程序之后再去装模型」——那是把交付做一半。
#   而「把解开的模型塞进 .app」意味着：模型没经过签名验证就躺在那儿，
#   谁改一下都能被加载。签名必须在**安装那一刻**被验，
#   而不只是「发布时验过一次」。
MODEL_PACKAGE_DIR="${MODEL_PACKAGE_DIR:-}"

RT="$ROOT/target/runtime-image"
OUT="$ROOT/target/app-image"
PKGOUT="$ROOT/target/pkg"

bytes() { find "$1" -type f -exec stat -f%z {} + 2>/dev/null | awk '{s+=$1} END{print s+0}'; }
mib() { awk -v b="$1" 'BEGIN{printf "%.2f", b/1048576}'; }

version_gt() {
  awk -v a="$1" -v b="$2" 'BEGIN {
    split(a, av, "."); split(b, bv, ".");
    for (i = 1; i <= 3; i++) {
      ai = (av[i] == "" ? 0 : av[i] + 0); bi = (bv[i] == "" ? 0 : bv[i] + 0);
      if (ai > bi) exit 0; if (ai < bi) exit 1;
    }
    exit 1;
  }'
}

verify_macos_minimum() {
  local app="$1"
  local declared actual f max_seen="0"
  declared="$(/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$app/Contents/Info.plist" 2>/dev/null || true)"
  if [ "$declared" != "$MACOS_MIN_VERSION" ]; then
    echo "X Info.plist 最低系统是 ${declared:-缺失}，应为 $MACOS_MIN_VERSION" >&2
    return 1
  fi
  while IFS= read -r f; do
    file -b "$f" 2>/dev/null | grep -q 'Mach-O' || continue
    actual="$(xcrun vtool -show-build "$f" 2>/dev/null | awk '$1 == "minos" { print $2; exit }')"
    if [ -z "$actual" ]; then
      echo "X 读不到 Mach-O 最低系统：$f" >&2
      return 1
    fi
    if version_gt "$actual" "$MACOS_MIN_VERSION"; then
      echo "X 包内组件要求 macOS ${actual}，高于 Info.plist 声明的 ${MACOS_MIN_VERSION}：$f" >&2
      return 1
    fi
    if version_gt "$actual" "$max_seen"; then max_seen="$actual"; fi
  done < <(find "$app" -type f | LC_ALL=C sort)
  echo "    最低系统闸门：Info.plist=${declared}，包内 Mach-O 最大 minos=${max_seen}"
}

# 把 ORT 两个核心 dylib 从 jar 抽到 App 的固定目录。发布 jar 不再保留可运行时提取的副本，
# 启动器会把 onnxruntime.native.path 锁定到这里，供逐库签名与 hardened runtime 校验。
stage_macos_onnx_natives() {
  local input="$1"
  local jar="$input/$JAR_NAME"
  local dest="$input/native/onnxruntime"
  local prefix="ai/onnxruntime/native/osx-aarch64"
  local names="libonnxruntime.dylib libonnxruntime4j_jni.dylib"
  local name entry
  mkdir -p "$dest"
  for name in $names; do
    entry="$prefix/$name"
    if ! zipinfo -1 "$jar" | awk -v wanted="$entry" '$0 == wanted { found=1 } END { exit(found ? 0 : 1) }'; then
      echo "X fat jar 缺少 macOS ORT 原生库：$entry" >&2
      return 1
    fi
    unzip -p "$jar" "$entry" > "$dest/$name" || return 1
    file -b "$dest/$name" | grep -q 'Mach-O' || {
      echo "X 抽出的 ORT 文件不是 Mach-O：$name" >&2; return 1;
    }
  done
  local core="$dest/libonnxruntime.dylib" jni="$dest/libonnxruntime4j_jni.dylib"
  local rpath normalized=0 old_core_dep core_dep_count
  xcrun install_name_tool -id '@loader_path/libonnxruntime.dylib' "$core" || return 1
  old_core_dep="$(xcrun otool -L "$jni" | awk 'NR>1 && /libonnxruntime[.0-9]*[.]dylib/ && !/4j_jni/ {print $1}')"
  core_dep_count="$(printf '%s\n' "$old_core_dep" | awk 'NF{n++} END{print n+0}')"
  [ "$core_dep_count" -eq 1 ] || {
    echo "X ORT JNI 应恰好依赖一个核心库，实际 $core_dep_count 个" >&2; return 1;
  }
  xcrun install_name_tool -id '@loader_path/libonnxruntime4j_jni.dylib' "$jni" || return 1
  xcrun install_name_tool -change "$old_core_dep" \
    '@loader_path/libonnxruntime.dylib' "$jni" || return 1
  while IFS= read -r rpath; do
    [ -n "$rpath" ] || continue
    if [ "$rpath" = "@loader_path" ]; then
      normalized=1
    elif [ "$normalized" -eq 0 ]; then
      xcrun install_name_tool -rpath "$rpath" '@loader_path' "$jni" || return 1
      normalized=1
    else
      xcrun install_name_tool -delete_rpath "$rpath" "$jni" || return 1
    fi
  done < <(xcrun otool -l "$jni" | awk '/cmd LC_RPATH/{seen=1;next} seen && /path /{print $2;seen=0}')
  [ "$normalized" -eq 1 ] || {
    echo "X ORT JNI 库没有可归一化的 LC_RPATH，无法保证只从同目录解析依赖" >&2; return 1;
  }
  if xcrun otool -l "$jni" \
      | awk '/cmd LC_RPATH/{seen=1;next} seen && /path /{if ($2 != "@loader_path") bad=1;seen=0} END{exit(bad ? 0 : 1)}'; then
    echo "X ORT JNI 库仍含 App 外部 LC_RPATH" >&2
    return 1
  fi
  zip -q -d "$jar" "$prefix/*.dylib" >/dev/null || return 1
  if zipinfo -1 "$jar" | awk -v p="$prefix/" 'index($0,p)==1 && $0 ~ /[.]dylib$/ { found=1 } END { exit(found ? 0 : 1) }'; then
    echo "X 发布 jar 仍含 macOS ORT dylib，运行时可能绕过 App 内签名库" >&2
    return 1
  fi
  echo "    ORT 原生库固定目录：Contents/app/native/onnxruntime（jar 内副本已移除）"
}

assert_sealed_onnx_natives() {
  local app="$1" dir="$1/Contents/app/native/onnxruntime" name
  [ -d "$dir" ] || { echo "X App 缺少固定 ORT 目录：$dir" >&2; return 1; }
  for name in libonnxruntime.dylib libonnxruntime4j_jni.dylib; do
    [ -f "$dir/$name" ] || { echo "X App 缺少 ORT 原生库：$name" >&2; return 1; }
    file -b "$dir/$name" | grep -q 'Mach-O' || return 1
  done
  if ! xcrun otool -l "$dir/libonnxruntime4j_jni.dylib" \
      | awk '/cmd LC_RPATH/{seen=1;next} seen && /path /{if ($2 == "@loader_path") ok=1; else bad=1;seen=0} END{exit(ok && !bad ? 0 : 1)}'; then
    echo "X ORT JNI 的 LC_RPATH 不是唯一固定的 @loader_path" >&2
    return 1
  fi
  if [ "$(xcrun otool -D "$dir/libonnxruntime.dylib" | tail -1)" != \
       '@loader_path/libonnxruntime.dylib' ]; then
    echo "X ORT 核心库 install-id 未固定到 @loader_path" >&2
    return 1
  fi
  if ! xcrun otool -L "$dir/libonnxruntime4j_jni.dylib" \
      | awk 'NR>1 && $1 == "@loader_path/libonnxruntime.dylib" {ok=1} END{exit(ok ? 0 : 1)}'; then
    echo "X ORT JNI 未固定依赖同目录核心库" >&2
    return 1
  fi
  if xcrun otool -L "$dir"/*.dylib \
      | awk '/^[[:space:]]+/{p=$1; if (p !~ /^@loader_path\// && p !~ /^\/System\// && p !~ /^\/usr\/lib\//) bad=1} END{exit(bad ? 0 : 1)}'; then
    echo "X ORT dylib 仍依赖 App 外部非系统路径" >&2
    return 1
  fi
  if zipinfo -1 "$app/Contents/app/$JAR_NAME" \
      | awk '$0 ~ /^ai\/onnxruntime\/native\/osx-aarch64\/.*[.]dylib$/ { found=1 } END { exit(found ? 0 : 1) }'; then
    echo "X App jar 仍含可提取的 ORT dylib" >&2
    return 1
  fi
  echo "    ok  两个 ORT dylib 仅存在于 App 固定签名目录"
}

# ---- 把「已签名的模型包」放进 jpackage 的输入目录 ----------------------------
#
# 【放在哪儿】
#   jpackage 的 --input 目录会被整个复制到 Contents/app 目录下，
#   所以放成 <input>/models/ner-package/ 之后，
#   程序里的位置就是 <jar 目录>/models/ner-package/
#   —— 与 ModelManager.BUNDLED_SUBDIR（com.litemask.ner.ModelManager）同一条规则。
#   **同一个事实只能有一处定义**：两边各写一个字符串，迟早会分叉。
#
# 【带什么】
#   模型包 zip **和它的 .sig**。两者必须一起走：
#   验签那一步是按「同名 + .sig」找签名文件的（ModelTrust.signatureFileFor），
#   只带 zip 不带 sig = 程序验不了 = 装不上。
#
# 【只带这两个文件】
#   刻意**不**整目录复制：会把开发残留（.bak、.DS_Store）打进发布件。
stage_model_package() {
  [ -z "$MODEL_PACKAGE_DIR" ] && return 0
  [ -d "$MODEL_PACKAGE_DIR" ] || { echo "模型包目录不存在：$MODEL_PACKAGE_DIR" >&2; exit 1; }
  # 模型包与它的签名必须成对：只有 zip 没有 sig → 程序验不了 → 装不上。
  #
  # 【为什么用 while read 而不是 `for z in "$DIR"/*.zip`】
  #   通配符展开的每个路径都可能带空格（macOS 上「应用程序」之类目录名很常见），
  #   `set -- $zips` 那种写法会把带空格的路径**拆成好几个词**。
  #   → 逐行读，不做任何词分割。
  picked=""
  count=0
  while IFS= read -r z; do
    [ -f "$z" ] || continue
    [ -f "$z.sig" ] || continue
    picked="$z"
    count=$((count + 1))
  done < <(find "$MODEL_PACKAGE_DIR" -maxdepth 1 -type f -name '*.zip' | LC_ALL=C sort)
  if [ "$count" -eq 0 ]; then
    echo "模型包目录里没有「zip + 同名 .sig」这一对：$MODEL_PACKAGE_DIR" >&2
    echo "（签名的文件名必须是 <包名>.zip.sig；没有签名的话程序不会安装它）" >&2
    exit 1
  fi
  if [ "$count" -gt 1 ]; then
    echo "模型包目录里有 $count 个带签名的模型包，不知道该带哪个：$MODEL_PACKAGE_DIR" >&2
    exit 1
  fi
  local dest="$1/models/ner-package"
  mkdir -p "$dest"
  cp "$picked" "$dest/"
  cp "$picked.sig" "$dest/"
  echo "    + 模型包 $(basename "$picked")（含签名）→ models/ner-package/"
}

# ---- 随附模型包**真的进包了吗**（fail-closed）------------------------------
#
# 【为什么必须查】
#   jpackage 把 --input 目录整体复制到 app 目录这件事，是**布局约定**，
#   不是被文档钉死的行为。而这一条一旦不成立，后果是：
#   「程序装好了，但首次启动说没有模型」——用户看到的是一个**功能缺失**的产品，
#   而真因是打包时文件没进去。
#   → 打包当场查一次，查不到就**停**。
assert_model_package_in_app() {
  [ -z "$MODEL_PACKAGE_DIR" ] && return 0
  local dir="$1/Contents/app/models/ner-package"
  if [ ! -d "$dir" ]; then
    echo "随附模型包没进 .app（找 $dir 失败）——这个包**不发**（用户装完会发现没有模型）" >&2
    exit 1
  fi
  local n
  n="$(find "$dir" -maxdepth 1 -name '*.zip' | wc -l | tr -d ' ')"
  if [ "$n" != "1" ]; then
    echo "随附模型包不完整（$dir）：必须有且只有一个 .zip" >&2
    exit 1
  fi
  if [ ! -f "$dir/$(basename "$(find "$dir" -maxdepth 1 -name '*.zip')").sig" ]; then
    echo "随附模型包缺签名文件（$dir）——程序验不了它，不会安装" >&2
    exit 1
  fi
  echo "    ok  随附模型包已进包"
}

echo "== 0/9 架构闸门 =="
# 【为什么要卡在这里】
#   本项目只在 Apple Silicon（arm64）上验证过：
#     - 推理原生库是 onnxruntime 的 **osx-aarch64**（1b 步会做平台原生库预检）；
#     - jlink 裁出来的运行时、jpackage 产出的应用都是**宿主架构**。
#   在 Intel 机器上跑这个脚本，jlink/jpackage 会给出 x86_64 的运行时与应用，
#   但 jar 里带的仍是 osx-aarch64 的推理原生库 →
#   **打包当场全绿，装到机器上第一次推理才炸**（而且报错是「找不到符号」，
#   离真因「架构不一致」很远）。
#   → 默认直接拒绝，并说清楚为什么。
#   【Universal 同理】两个架构的产物没合过、没验过，就不许宣称「支持 Universal」。
# ---- 可选的 Developer ID 签名 ----
#
# 【为什么需要它，以及为什么默认不做】
#   jpackage 在 macOS 上**能**签（--mac-sign）：应用用 Developer ID Application、
#   .pkg 用 Developer ID Installer（两种不同的证书）。
#   但它**不做公证**、不装订——公证与装订是 tools/package-macos-sign-notarize.sh 的事。
#   没有证书时产物只能是 ad-hoc 候选：Gatekeeper 会拦，不能对外发。
#   → 这里把「有没有证书」变成显式开关，签了就在记录里写明签了。
SIGN_IDENTITY="${LITEMASK_SIGN_IDENTITY:-}"
if [ -z "$SIGN_IDENTITY" ] && [ "${LITEMASK_MAC_SIGN:-}" = "1" ]; then
  SIGN_IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null | grep 'Developer ID Application:' | head -1 | sed -n 's/.*"\(.*\)".*/\1/p')"
  if [ -z "$SIGN_IDENTITY" ]; then
    echo "X 找不到 Developer ID Application 身份：钥匙串里没有可用的发布证书。" >&2
    echo "  解除办法：导入发布主体的 Developer ID Application 证书，或不设 LITEMASK_MAC_SIGN（出 ad-hoc 候选包）。" >&2
    exit 1
  fi
fi
MAC_SIGN_ARGS=()
if [ -n "$SIGN_IDENTITY" ]; then
  MAC_SIGN_ARGS=(--mac-sign)
  [ -n "${LITEMASK_SIGN_KEYCHAIN:-}" ] && MAC_SIGN_ARGS+=(--mac-signing-keychain "${LITEMASK_SIGN_KEYCHAIN}")
  [ -n "${LITEMASK_SIGN_KEY_USER:-}" ] && MAC_SIGN_ARGS+=(--mac-signing-key-user-name "${LITEMASK_SIGN_KEY_USER}")
fi

HOST_ARCH="$(uname -m)"
if [ "$HOST_ARCH" != "arm64" ]; then
  if [ "${LITEMASK_ALLOW_UNVERIFIED_ARCH:-}" = "1" ]; then
    echo "    ⚠ 按 LITEMASK_ALLOW_UNVERIFIED_ARCH=1 继续：产物架构为 ${HOST_ARCH}，**未验证**，不得对外宣称支持该架构"
  else
    echo "X 拒绝在 ${HOST_ARCH} 上打 macOS 包：本项目只在 Apple Silicon（arm64）上验证过。" >&2
    echo "  推理原生库是 osx-aarch64，而 jlink/jpackage 按宿主产出 ${HOST_ARCH} 的运行时与应用，" >&2
    echo "  两者不一致 → 打包当场全绿、第一次推理才炸。" >&2
    echo "  解除办法：在 Apple Silicon 上构建；或先补齐并验证 ${HOST_ARCH} 的原生库与运行时。" >&2
    echo "  确实要出「未验证候选包」时：LITEMASK_ALLOW_UNVERIFIED_ARCH=1 ./tools/package-macos.sh" >&2
    exit 1
  fi
else
  echo "    宿主架构：arm64（Apple Silicon，已验证；Intel 与 Universal 未验证，不宣称支持）"
fi

echo "== 1/9 打 fat jar =="
# ---- 离线优先 + 失败才预热（2026-10-22 块 28；与 tools/package-windows.ps1 同一形态）----
#   原来这里写死了 `-o`（离线）。开发机本地仓库已预热，没问题；
#   但换一台干净机器（仓库是空的）→ 离线**必然失败**，报的还是
#   "Cannot access central ... in offline mode"，看不出真因是「把 -o 写死了」。
#   → 改成回退链：先离线；离线失败（仓库不完整）才联网预热一次，
#     然后**仍然用离线**构建出包。出包那一次构建始终是离线的。
#   实测依据（work/maven-fallback-probe.sh，三步全跑）：
#     空仓库 + 离线 → RC=1 / 1s / 仓库内 .lastUpdated = 0 个
#     联网预热（同一份 settings）→ RC=0 / 27s
#     预热后 + 离线（同一份 settings）→ RC=0 / 2s
#   → 仓库已预热时只构建一次（无条件两段式会构建两次，白多花一次时间）
if ! "$MVN_BIN" "${MVN_ARGS[@]}" -o -q -DskipTests package; then
    echo "    离线构建失败 —— 本地 Maven 仓库不完整，联网预热一次"
    if ! "$MVN_BIN" "${MVN_ARGS[@]}" -q -DskipTests package; then
        echo "    联网预热也失败 —— 检查网络，或用 MAVEN_SETTINGS 指向带镜像的 settings.xml"
        exit 1
    fi
    echo "    预热完成，重新用离线构建出包（保证出包过程不触网）"
    if ! "$MVN_BIN" "${MVN_ARGS[@]}" -o -q -DskipTests package; then
        echo "    预热后的离线构建仍失败 —— 本地仓库仍不完整"
        exit 1
    fi
fi

# ---- 剔掉另一个平台的 ONNX Runtime 原生库 ----
# com.microsoft.onnxruntime 的 Maven 包**同时带 win-x64 与 osx-aarch64** 两套 .dll/.dylib
# （实测：win-x64 11.65 MB、osx-aarch64 25.56 MB 未压缩）。
# 发布到 macOS 就不该带 Windows 的那套——纯浪费，且体积是本项目唯一的硬指标（90 MiB）。
#   实测收益：macOS 包省 4.26 MiB（jar 16.97 → 12.71 MiB），Windows 包省 6.57 MiB。
# 已验证剔掉 win-x64 后 ONNX 加载与推理结果**逐字不变**（主集 F1 0.8479）。
#
# 为什么在打包脚本里做、不在 pom 里：pom 是静态的，无法按目标平台变化；
# 而放这里正好与「jlink 模块集是唯一定义处」同一层——发布形态相关的裁剪都在打包阶段。
#
# ⚠️ 这里**不用** `unzip -l | grep -q` 来判断「有没有 win-x64」：
#   本脚本开头是 `set -o pipefail`，而 `grep -q` 命中即退出、让 unzip 收到 SIGPIPE
#   （退出码 141），pipefail 会把整个管道判成失败 → if 条件为假 → 静默不剪。
#   **这个 bug 已经真实发生过一次**：剪除逻辑第一次跑就完全没执行，
#   而打包照样打印「PASS 打包并验证通过」。
#   改成无条件执行、再用**体积是否真的变小**来判断——退出码不再是证据，体积才是。
before=$(wc -c < "$ROOT/target/$JAR_NAME" | tr -d ' ')
zip -q -d "$ROOT/target/$JAR_NAME" 'ai/onnxruntime/native/win-x64/*' >/dev/null 2>&1
JAR_BYTES=$(wc -c < "$ROOT/target/$JAR_NAME" | tr -d ' ')
if [ "$JAR_BYTES" -lt "$before" ]; then
  echo "    已剔除 win-x64 原生库：省 $(( before - JAR_BYTES )) B = $(mib $(( before - JAR_BYTES ))) MiB"
else
  # 没变小就说明依赖树变了（比如上游不再带 win-x64），这不是错误，但要说明白，
  # 否则读的人会以为「剪过了」而其实什么都没发生。
  echo "    未找到 win-x64 原生库可剔除（上游依赖树可能已变）——jar 未变小，如实报告"
fi
echo "    $JAR_NAME = $JAR_BYTES B = $(mib "$JAR_BYTES") MiB"

# ---- 平台原生库完整性预检（fail-closed）----
# 【为什么要这一步 —— 它挡的是「静默少东西」】
#   上面那段「剔掉 win-x64」只保证「**别的**平台那套没了」，
#   **不保证「自己那套还在」**。上游依赖树一变（比如 ONNX Runtime 换版、
#   或新版本不再带 win-x64），这个包会**静默**地少掉原生库 ——
#   装到用户机器上不报错，跑模型的时候才炸。
#   → 预检把「该有什么 / 不该有什么」变成一条**会红的判据**。
#   策略表 tools/platform-native-policy.properties 是**唯一真源**，
#   Windows 那边的 tools/package-windows.ps1 读的是同一张表。
echo "== 1b/9 平台原生库完整性预检 =="
if ! "$JAVA_HOME/bin/java" "$ROOT/tools/CheckPlatformNatives.java" \
      --policy "$ROOT/tools/platform-native-policy.properties" \
      --platform macos-arm64 "$ROOT/target/$JAR_NAME"; then
  echo "打包失败：这个包缺 osx-aarch64 原生库（或带了不该有的）——不要发"
  exit 1
fi

echo "== 2/9 jlink 定制运行时（${MODULES}）=="
[ -d "$RT" ] && rm_rf "$RT"
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages --compress=2 \
  --output "$RT" 2>&1 | grep -v 'deprecated' || true
RT_BYTES=$(bytes "$RT")
echo "    运行时 = $RT_BYTES B = $(mib "$RT_BYTES") MiB"
echo "    实际模块：$("$RT/bin/java" --list-modules | cut -d@ -f1 | tr '\n' ' ')"

# ---- 裁剪运行时**能力**自检（2026-10-25 加，与 Windows 侧同一份判据）----
#
# jlink 把运行时裁到只剩几个模块，而裁掉的东西里有一个是**产品要用的**：
# `KeyFactory.getInstance("Ed25519")` 的实现在 **jdk.crypto.ec**（sun.security.ec.ed），
# **不在 java.base**。
# → 少了它，装好的产品里**模型包一件都装不上**（model install 报
#   「Ed25519 KeyFactory not available」），而核心包照常工作。
#   这条缺陷单测永远抓不到（单测跑在完整 JDK 上）。
# → 用**裁剪出来的那份 java** 跑一遍能力自检，缺一项就停。
#   判据在产品里（com.litemask.pack.RuntimeCapabilityProbe），跑的就是要发出去的那个 jar。
echo "== 2b/9 裁剪运行时能力自检（用裁剪后的 java 跑）=="
if ! "$RT/bin/java" -cp "$ROOT/target/$JAR_NAME" com.litemask.pack.RuntimeCapabilityProbe; then
  echo "打包失败：裁剪运行时缺少产品要用的能力（例如 Ed25519 验签）——这个包不能发" >&2
  exit 1
fi

echo "== 3/9 jpackage 打 .app =="
STAGE="$(mktemp -d)"
cp "$ROOT/target/$JAR_NAME" "$STAGE/"
stage_macos_onnx_natives "$STAGE" || { rm_rf "$STAGE"; exit 1; }
# 随程序分发的已签名模型包（如果有）：放进 --input 目录，
# jpackage 会把整个输入目录复制到 Contents/app → 程序里的位置是
# <jar 目录>/models/ner-package/，与 ModelManager.BUNDLED_SUBDIR 同一条规则。
stage_model_package "$STAGE"
rm_rf "$OUT"
"$JAVA_HOME/bin/jpackage" --type app-image \
  --name "$APP_NAME" --app-version "$APP_VERSION" \
  --input "$STAGE" --main-jar "$JAR_NAME" --main-class "$MAIN_CLASS" \
  --runtime-image "$RT" --dest "$OUT" --resource-dir "$RESOURCE_STAGE" \
  --java-options "$JVM_ARGS" || { echo "jpackage 失败"; rm_rf "$STAGE"; exit 1; }
rm_rf "$STAGE"
APP="$OUT/$APP_NAME.app"
echo "    $APP"
# 随附模型包真的进包了吗（fail-closed）
assert_model_package_in_app "$APP"
assert_sealed_onnx_natives "$APP" || exit 1
verify_macos_minimum "$APP" || exit 1

fail=0

echo "== 4/9 先验「没装模型」这一态 =="
# 为什么要先验没模型的形态：方案 B 下核心包是**不带模型**的，
# 而「不带模型时必须明说人名没被脱敏」是 M3-2 的一条硬要求。
# 只在装了模型的形态上验，等于把这一态留给用户去发现。
#
# ⚠️ 这一态必须**隔离 user.home** 才测得到。加载器会依次找
#   <程序目录>/models/ner 和 ~/.litemask/models/ner，
# 而开发者只要在自己机器上按 README 装过模型，~/.litemask/models/ner 就存在——
# 于是这一步测的其实是「装了模型」，却拿「没装模型」的判据去判，红得莫名其妙。
# （本项目真的踩过：为了验证测试隔离把模型装回了 ~/.litemask，
#   结果打包脚本第 4 步直接判红，而红的原因与包本身无关。）
#
# 为什么用 JAVA_TOOL_OPTIONS 而不是 env -i HOME=...：
#   macOS/Unix 的 JVM 取 user.home 走 passwd 而不是 HOME 环境变量，改 HOME 无效（已实测）。
#   JDK_JAVA_OPTIONS 对 jpackage 的原生 launcher **不生效**（已实测，仍会找到真实模型），
#   而 JAVA_TOOL_OPTIONS 会被 JVM 读取（已实测：它打印了「依次找过 …/empty-home/.litemask/…」）。
WS="$(mktemp -d)"
EMPTY_HOME="$WS/empty-home"
mkdir -p "$EMPTY_HOME"
export JAVA_TOOL_OPTIONS="-Duser.home=${EMPTY_HOME}"
printf '张三丰的身份证是11010519491231002X，手机13800138000。\n' > "$WS/t.txt"
OUT="$WS/t.redacted.txt"
if "$APP/Contents/MacOS/$APP_NAME" redact "$WS/t.txt" > "$WS/nomodel.log" 2>&1; then
  if [ ! -f "$OUT" ]; then
    echo "    ng  无模型形态的产物名不对（期望 ${OUT}，实际：$(ls -1 "$WS" | tr '\n' ' '))"
    fail=1
  elif ! grep -q '<身份证1>' "$OUT"; then
    echo "    ng  无模型形态没产出 <身份证1>"; sed 's/^/      /' "$WS/nomodel.log"; fail=1
  elif ! grep -qE '没有脱敏|未启用' "$WS/nomodel.log"; then
    echo "    ng  没装模型却没有声明「人名与机构名没有被脱敏」——用户会以为脱干净了"
    sed 's/^/      /' "$WS/nomodel.log"; fail=1
  elif grep -q '<人名1>' "$OUT"; then
    echo "    ng  没装模型却产出了 <人名1>——模型是从别处被捡到了"
    grep -q 'empty-home' "$WS/nomodel.log" \
      && echo "      （已确认本次 user.home 被隔离到 ${EMPTY_HOME}，若真实 home 里有模型，隔离失效）"
    fail=1
  elif ! grep -q 'empty-home' "$WS/nomodel.log"; then
    # 证明这次「没找到模型」不是因为恰好开发机没装模型，而是**确实隔离到了空 home**。
    # 否则这条验证和「开发机碰巧干净」无法区分。
    echo "    ng  隔离没生效：日志里没有出现 empty-home 路径，无法证明测的是隔离后的形态"
    sed 's/^/      /' "$WS/nomodel.log"; fail=1
  else
    echo "    ok  无模型时照常脱敏，且明说人名与机构名没有被脱敏（user.home 已隔离到空目录）"
  fi
else
  echo "    ng  无模型形态 CLI 失败："; sed 's/^/      /' "$WS/nomodel.log"; fail=1
fi
unset JAVA_TOOL_OPTIONS

echo "== 5/9 装模型包（按 manifest 声明的文件白名单，不整目录复制）=="
if [ -n "$MODEL_DIR" ]; then
  # 【块 29 修掉的真缺陷：模型目录必须按「jar 所在目录」推导】
  #   加载器 NerModelRegistry.programDirectory() = CodeSource 的**父目录**，
  #   也就是 **jar 所在的目录**；它只在那里找 models/ner。
  #   （user.home/.litemask/models/ner 是**第二**候选，不是第一。）
  #   → 原来这里写死 "$APP/Contents/app/models/ner"。这个值**当前恰好是对的**
  #     （jpackage app-image 确实把 jar 放在 Contents/app/），但它是**写死**的：
  #     jpackage 布局一变就会装到加载器不看的地方，而**没有任何东西会报红**
  #     ——「装了模型」那一态会在第 7 步才红，且红的现象是「模型没被发现」，
  #     指向的排查方向（模型包坏没坏）与真因（装错地方）完全无关。
  #   → 现在按 jar 的**实际位置**推导，并且**断言 jar 真的在那里**：
  #     布局变了会在这里立刻报红，而不是拖到第 7 步。
  #     （与 Windows 侧 Resolve-ModelDir 是**同一条规则**，
  #       差别只在各平台怎么找 jar —— 规则本身只有一条。）
  JAR_IN_APP="$APP/Contents/app/$JAR_NAME"
  if [ ! -f "$JAR_IN_APP" ]; then
    # 兜底：在 Contents 下找一遍（jpackage 布局若变，这里还能找到）。
    # ⚠ 不用 `find | head`：本脚本开了 pipefail，find 被 SIGPIPE 打断会返回非 0
    #   → 整条命令失败 → 明明找到了 jar 却走「找不到」的分支。
    #   `-print -quit` 让 find 自己停，不需要下游截断。
    JAR_IN_APP="$(find "$APP/Contents" -type f -name "$JAR_NAME" -print -quit 2>/dev/null || true)"
  fi
  if [ -z "$JAR_IN_APP" ] || [ ! -f "$JAR_IN_APP" ]; then
    # fail-closed：模型目录无从判断时**不猜**。猜一个目录 = 把模型装到
    # 加载器不看的地方，而**没有任何东西会报红**（要等第 7 步才红，
    # 而红的现象是「模型没被发现」，指向的排查方向与真因完全无关）。
    echo "X 在 $APP/Contents 下找不到 $JAR_NAME —— 模型目录无从判断（jpackage 布局变了？）"
    fail=1
  else
    DEST="$(dirname "$JAR_IN_APP")/models/ner"
    echo "    jar 所在目录 = $(dirname "$JAR_IN_APP")"
    echo "    模型目录 = $DEST"
    mkdir -p "$DEST"
    # 只拷 manifest + manifest 里 files 段声明的那些。
    # 整目录 `cp *` 会把开发残留（比如 .bak）一起打进发布件——本轮就真的带进去过一个
    # 549 字节的 v1 旧 manifest。加载器本身是**忽略**未声明文件的（有意为之：
    # macOS 会在用户用 Finder 打开过目录后塞 .DS_Store 进来，拒绝未声明文件
    # 会让模型包在用户的一次正常点击后失效），所以它不会报错，只会安静地多带一个文件。
    python3 - "$MODEL_DIR" "$DEST" <<'PY'
import json, os, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
mf = json.load(open(os.path.join(src, "manifest.json"), encoding="utf-8"))
names = ["manifest.json"] + [v["path"] for v in mf.get("files", {}).values()]
for n in names:
    shutil.copy2(os.path.join(src, n), os.path.join(dst, n))
    print("    + " + n)
PY
  fi
else
  echo "    （未指定 MODEL_DIR，核心包不带模型——符合方案 B）"
fi

echo "== 6/9 签名（加过文件后原签名失效，必须重签）=="
# 【这一签名是 ad-hoc】——它是「本地能跑」的封口，不是「用户能装」的封口。
#   正式分发（Developer ID + 公证 + 装订）走 tools/package-macos-sign-notarize.sh。
# 【有 Developer ID 身份时**不能**再 ad-hoc 签一次】
#   ad-hoc 签名会把 Developer ID 签名**整个覆盖掉**（--force），
#   于是产物看起来「签过了」，实际上分发时 Gatekeeper 照样拦。
APP_AUTHORITY=""
if [ -n "$SIGN_IDENTITY" ]; then
  codesign --verify --deep --strict "$APP" 2>&1 | sed 's/^/    /' && echo "    签名校验通过（Developer ID）"
  APP_AUTHORITY="$(codesign -dv "$APP" 2>&1 | sed -n 's/^Authority=//p' | head -1)"
  echo "    签名身份：${APP_AUTHORITY:-未知}"
else
  codesign --force --deep --sign - "$APP" 2>&1 | sed 's/^/    /'
  codesign --verify --deep --strict "$APP" 2>&1 | sed 's/^/    /' && echo "    签名校验通过（ad-hoc 候选）"
  APP_AUTHORITY="ad-hoc"
fi

APP_BYTES=$(bytes "$APP")
echo "    $APP_NAME.app = $APP_BYTES B = $(mib "$APP_BYTES") MiB"

echo "== 7/9 验产物形态：真的把它跑起来 =="
# 【块 29 把这一步拆成了两种形态】
#   这一步的名字原本叫「验『装了模型』这一态」，但**本次打包可能根本没装模型**
#   （方案 B：核心包不带模型，MODEL_DIR 为空）。
#   原来判据是**一刀切**的：没有 <人名1> 就报「模型包没被自动发现」。
#   → 在「不带模型」这一态下，那条判据**必然失败**（包里确实没模型），
#     而红的原因说的是「模型包没被自动发现」——指向的排查方向（模型包坏了）
#     与真因（这次压根没装模型）完全无关。**红灯原因不正确**。
#   → 现在按形态分判：
#     · 装了模型：必须有 <人名1>，且必须自证隔离生效
#       （唯一能命中的是 <jar 目录>/models/ner，正是第 5 步装的位置）。
#     · 没装模型：**不应**有 <人名1>，且必须**明说**人名没脱敏，
#       且必须自证隔离生效 —— 这样才能区分
#       「包内没有模型（预期）」与「模型从用户 home 捡来了（不该发生）」。
# 6a. 命令行形态仍然可用（同一个可执行文件在终端里是完整 CLI）
#
# 这条同时验两件事：产物名对不对（.txt 现在走 redactedName，应该是 t.redacted.txt），
# 以及**模型包是不是被自动发现了**——不传 --ner-model，
# 只有当加载器能在 <程序目录>/models/ner 找到模型时，人名才会被脱敏成 <人名1>。
# 【块 29 补的一道：这一步也必须隔离 user.home】
#   「模型被自动发现」这条判据，只有在**用户 home 里没有模型**时才是真的。
#   加载器的查找顺序是：<jar 目录>/models/ner → <user.home>/.litemask/models/ner。
#   开发者只要在自己机器上按 README 装过模型（**本机就有**：
#   ~/.litemask/models/ner 里确实躺着 ner.onnx + vocab.txt），
#   那么即使**打包时把模型装错了地方**（装到加载器不看的位置），
#   这一步仍会从 home 捡到模型 → 照样产出 <人名1> → **照样绿灯**。
#   → 那这条判据就成了**恒真**：它证明不了「模型被自动发现了」，
#     只证明「这台机器上某个地方有个模型」。
#   → 隔离成空 home 之后，唯一能命中的是 <jar 目录>/models/ner，
#     而那正是第 5 步装的位置 —— 这条判据才真的在验它该验的东西。
#
# 为什么用 JAVA_TOOL_OPTIONS（不是 JDK_JAVA_OPTIONS，也不是改 HOME）：
#   JDK_JAVA_OPTIONS 对 jpackage 的原生 launcher **不生效**（已实测）；
#   JAVA_TOOL_OPTIONS 会被 JVM 读取（已实测：它打印了
#   「Picked up JAVA_TOOL_OPTIONS: -Duser.home=...」且加载器日志里
#   出现了 empty-home 下的候选路径）。
EMPTY_HOME2="$WS/empty-home2"
mkdir -p "$EMPTY_HOME2"
export JAVA_TOOL_OPTIONS="-Duser.home=${EMPTY_HOME2}"
printf '张三丰的身份证是11010519491231002X，手机13800138000。\n' > "$WS/t2.txt"
if "$APP/Contents/MacOS/$APP_NAME" redact "$WS/t2.txt" > "$WS/out.log" 2>&1; then
  OUT="$WS/t2.redacted.txt"
  MAP2="$WS/t2.txt.redacted.map.json"
  if [ ! -f "$OUT" ]; then
    echo "    ng  产物名不对：期望 ${OUT}，实际有：$(ls -1 "$WS" | tr '\n' ' ')"
    fail=1
  elif ! grep -q '<身份证1>' "$OUT"; then
    echo "    ng  .app 内 CLI 退出码 0 但没产出 <身份证1>："; sed 's/^/      /' "$WS/out.log"
    fail=1
  elif [ -n "$MODEL_DIR" ]; then
    # ---------- 形态一：装了模型 ----------
    # 「模型被自动发现」这条判据，只有在**用户 home 里没有模型**时才是真的。
    #   加载器的查找顺序是：<jar 目录>/models/ner → <user.home>/.litemask/models/ner。
    #   开发者只要在自己机器上按 README 装过模型（**本机就有**：
    #   ~/.litemask/models/ner 里确实躺着 ner.onnx + vocab.txt），
    #   那么即使**打包时把模型装错了地方**（装到加载器不看的位置），
    #   这一步仍会从 home 捡到模型 → 照样产出 <人名1> → **照样绿灯**。
    #   → 那条判据就成了**恒真**：它证明不了「模型被自动发现了」，
    #     只证明「这台机器上某个地方有个模型」。
    #   → 隔离成空 home 之后，唯一能命中的是 <jar 目录>/models/ner，
    #     而那正是第 5 步装的位置 —— 这条判据才真的在验它该验的东西。
    #
    #   【本块实测到的证据】块 28 时代的链日志里，这一步是 **ok**（绿），
    #   而那一次打包的第 5 步明确写着
    #   「（未指定 MODEL_DIR，核心包不带模型——符合方案 B）」。
    #   → 也就是说：**那次绿灯完全靠开发机 home 里的模型撑着**。
    #   → 隔离之后，同一次打包的这一步立刻报红，而且红得**对**：包内确实没模型。
    #   → 这正是「恒真判据被拆穿」的样子：**以前绿得没有依据，现在红得有依据**。
    #
    # 为什么用 JAVA_TOOL_OPTIONS（不是 JDK_JAVA_OPTIONS，也不是改 HOME）：
    #   JDK_JAVA_OPTIONS 对 jpackage 的原生 launcher **不生效**（已实测）；
    #   JAVA_TOOL_OPTIONS 会被 JVM 读取（已实测：它打印了
    #   「Picked up JAVA_TOOL_OPTIONS: -Duser.home=...」且加载器日志里
    #   出现了 empty-home 下的候选路径）。
    if ! grep -q '<人名1>' "$OUT"; then
      echo "    ng  没有 <人名1>——模型包没被自动发现（.app 内的 models/ner 路径没生效）"
      sed 's/^/      /' "$WS/out.log"; fail=1
    elif ! grep -q 'empty-home2' "$WS/out.log"; then
      # 【自证：证明这次「模型被自动发现」是因为 .app 内的 models/ner 生效，
      #   而不是因为这台机器上别处有个模型】
      #   「已启用」形态下加载器**不打印**候选路径（describe() 走已启用分支），
      #   所以不能像第 4 步那样靠「依次找过」自证 ——
      #   只能靠 JVM 自己打印的「Picked up JAVA_TOOL_OPTIONS: -Duser.home=…」这一行。
      #   没有这一行 = 隔离没生效 = 这条判据在验一台碰巧装了模型的机器。
      echo "    ng  隔离没生效：日志里没有 empty-home2 路径，无法证明模型是从 .app 内被发现的"
      sed 's/^/      /' "$WS/out.log"; fail=1
    elif ! grep -q '<手机号1>' "$OUT"; then
      # 前缀是从 src/main/resources/rules/core-rules.json 的 tokenPrefix 抄来的，
      # 不是猜的——之前猜成「手机」就让这条判据在打包产物里第一次跑就红了，
      # 而红灯说的是「规则没接上」，指向完全错误的排查方向。
      echo "    ng  没有 <手机号1>——规则链路在打包产物里没接上"
      sed 's/^/      /' "$WS/out.log"; fail=1
    else
    # 上面三条只验「该有的令牌在」。这条验「不该有的东西不在」——
    # 只查令牌存在的话，一个把命中拆成碎片、把尾巴漏在文件里的实现照样能过。
    LEAK=""
    for raw in '11010519491231002X' '13800138000' '张三丰'; do
      if grep -q "$raw" "$OUT"; then LEAK="$LEAK $raw"; fi
    done
    # 碎片也要查：一个 18 位身份证被切成三条命中时，每条都不等于原文，正则查不出来，
    # 但页面上就只剩「110 1051 9491231002 X」这种读不出是不是敏感信息的东西。
    # 这里查的是「文件里除令牌与正文标点外，不该出现连续 6 位以上裸数字」。
    if printf '%s' "$(sed 's/<[^>]*>//g' "$OUT")" | grep -qE '[0-9]{6,}'; then
      LEAK="${LEAK} 连续6位以上裸数字"
    fi
    if [ -n "$LEAK" ]; then
      echo "    ng  脱敏件里仍有原文或碎片：${LEAK}"
      echo "      脱敏件全文："; sed 's/^/        /' "$OUT"
      fail=1
    elif [ ! -f "$MAP2" ]; then
      echo "    ng  映射文件没生成：$MAP2"
      fail=1
    elif ! grep -q '11010519491231002X' "$MAP2" || ! grep -q '13800138000' "$MAP2" \
         || ! grep -q '张三丰' "$MAP2"; then
      # 映射缺原值 = 还原必然失败，而这在界面上看不出来：用户只会看到「还原后少了一段」。
      echo "    ng  映射文件里没有完整的原值（身份证/手机号/姓名 三者必须都在）"
      sed 's/^/      /' "$MAP2"; fail=1
    else
      # 往返必须真的能回来。打包产物用的是 jlink 精简运行时 + 另写的启动器，
      # 与开发时跑的那些不是同一个二进制——只验「能脱敏」不验「能还原」是不够的。
      # 还原件名按**脱敏件**推，且 .restored 插在扩展名之前：
      # t2.redacted.txt → t2.redacted.restored.txt（这与 CLI 的 restoredName 同规则）。
      #
      # 这里连着踩了三次：先写成 t2.restored.txt，再写成 t2.redacted.txt.restored.txt，
      # 两次 cmp 都「失败」，红灯都说「还原坏了」，而实际还原早就成功了。
      # **判据里凡是拼出来的路径，都要能从渲染输出里核对**——
      # 所以下面失败时会把期望路径与是否存在一起打出来。
      # OUT 是绝对路径，所以要**先取 basename** 再去扩展名：
      # 直接 ${OUT%.*} 会把目录一起留下，拼出 $WS/<绝对路径> 这种双份路径。
      # 上面那句失败提示就是把双份路径原样打出来的，靠它一眼看出来。
      BACK_NAME="${OUT##*/}"          # t2.redacted.txt
      BACK_EXT="${BACK_NAME##*.}"     # txt
      BACK_STEM="${BACK_NAME%.*}"     # t2.redacted
      BACK="$WS/${BACK_STEM}.restored.${BACK_EXT}"
      if "$APP/Contents/MacOS/$APP_NAME" restore "$OUT" --map "$MAP2" > "$WS/restore.log" 2>&1 \
         && [ -f "$BACK" ] && cmp -s "$WS/t2.txt" "$BACK"; then
        echo "    ok  .app 内 CLI 脱敏正常、原文零残留、映射完整、往返逐字一致（模型已被自动发现）"
      else
        echo "    ng  .app 内还原失败或往返不一致"
        sed 's/^/      /' "$WS/restore.log"
        # 注意 ${BACK} 的花括号：裸 $BACK 后面紧跟全角括号会被 bash 吞进变量名，
        # 报出来的却是 `BACK?: unbound variable`——指向一个根本不存在的变量。
        echo "      期望的还原件：${BACK}（存在？$([ -f "${BACK}" ] && echo 是 || echo 否)）"
        diff "$WS/t2.txt" "$BACK" 2>/dev/null | sed 's/^/      /' | head -10
        fail=1
      fi
    fi
    fi
  else
    # ---------- 形态二：没装模型（方案 B：核心包不带模型）----------
    #
    # 这一态的判据与第 4 步**同源**：没装模型时必须**明说**人名没脱敏，
    # 而且**绝不能**出现 <人名1>（出现了就说明模型是从用户 home 捡来的）。
    # → 而「绝不能出现 <人名1>」这条，**只有隔离了 user.home 才成立**。
    #
    # 【为什么必须分形态，不能一刀切】
    #   这一步原本一刀切地要求 <人名1> 出现。在「不带模型」这一态下，
    #   包里确实没有模型 → 那条判据**必然失败**，
    #   而红的原因说的是「模型包没被自动发现」——指向的排查方向（模型包坏了）
    #   与真因（这次压根没装模型）完全无关。
    #   → **红灯原因必须正确**：这一态该红的是「模型没被发现」，
    #     这一态该绿的是「没装模型且明说了没脱敏」。
    if grep -q '<人名1>' "$OUT"; then
      echo "    ng  核心包不带模型，却产出了 <人名1>——模型是从别处（用户 home）捡来的"
      sed 's/^/      /' "$WS/out.log"; fail=1
    elif ! grep -q 'empty-home2' "$WS/out.log"; then
      echo "    ng  隔离没生效：日志里没有 empty-home2 路径，无法证明「没有 <人名1>」是因为包内确实没模型"
      sed 's/^/      /' "$WS/out.log"; fail=1
    elif ! grep -qE '没有脱敏|未启用' "$WS/out.log"; then
      echo "    ng  没装模型却没有声明「人名与机构名没有被脱敏」——用户会以为脱干净了"
      sed 's/^/      /' "$WS/out.log"; fail=1
    else
      echo "    ok  核心包不带模型时：规则类照常脱敏，人名明说没脱（且不是从用户 home 捡来的模型）"
    fi
  fi
else
  echo "    ng  .app 内 CLI 脱敏失败："; sed 's/^/      /' "$WS/out.log"; fail=1
fi
# 第 7 步的隔离到此为止：**必须 unset**，
# 否则第 8 步的 jpackage（以及任何后面调 java 的命令）会带着
# -Duser.home=<空目录> 跑 —— 那是打包工具自己的 home，不是用户的。
unset JAVA_TOOL_OPTIONS

# 6b. 无参数 = 桌面形态：起本地服务并能被外部访问
PORT=19422
"$APP/Contents/MacOS/$APP_NAME" serve --port "$PORT" --workspace "$WS/ws" --no-browser \
  > "$WS/serve.log" 2>&1 &
SRV=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do sleep 1; curl -s -m 2 -o /dev/null "http://127.0.0.1:$PORT/api/health" && break; done
if curl -s -m 3 "http://127.0.0.1:$PORT/api/health" | grep -q '"ok":true'; then
  echo "    ok  .app 起服务并通过 /api/health"
else
  echo "    ng  .app 起服务失败："; sed 's/^/      /' "$WS/serve.log"; fail=1
fi
kill $SRV 2>/dev/null; wait $SRV 2>/dev/null

# ---- 7b. 首次启动**自动**把随附模型装上（这是产品的核心承诺）--------------
#
# 【为什么必须单独验这一条】
#   前面第 5 步是用**命令行**装模型的——那是给排障与脚本用的路径。
#   而产品承诺的是：**用户双击装好、打开应用，模型自己就绪**，
#   全程不碰终端。这一条只能靠「起服务 → 问 /api/model」来验。
#
# 【怎么做到可信】
#   用一个**全新的空 home** 起服务 → 服务里没有模型；
#   然后轮询 /api/model，等它自己变成 READY。
#   模型是从**包内**的随附模型包装上来的（验签 + 原子换装 + 真的加载），
#   所以状态变成 READY 只能是因为「自动安装这条路真的通了」。
#
# 【用另一个端口】
#   上一个服务刚被杀掉，同一个端口可能还在 TIME_WAIT；
#   而更糟的是：两个服务共用一个端口时，第二次的健康检查可能打到第一个上，
#   于是「第二次起没起来」这件事根本没被验到。
if [ -n "$MODEL_PACKAGE_DIR" ]; then
  AUTO_PORT=19423
  EMPTY_HOME3="$WS/empty-home3"
  mkdir -p "$EMPTY_HOME3"
  JAVA_TOOL_OPTIONS="-Duser.home=$EMPTY_HOME3" \
  "$APP/Contents/MacOS/$APP_NAME" serve --port "$AUTO_PORT" --workspace "$WS/ws2" --no-browser \
    > "$WS/serve-auto.log" 2>&1 &
  SRV2=$!
  MODEL_READY=""
  STATE_SEEN=""
  # 装模型要解压 13MB 并把 ONNX 模型加载起来：给足 180 秒。
  i=0
  while [ $i -lt 180 ]; do
    sleep 1
    RESP="$(curl -s -m 3 "http://127.0.0.1:$AUTO_PORT/api/model" 2>/dev/null)"
    case "$RESP" in
      *'"state"'*)
        ST="$(printf '%s' "$RESP" | sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p')"
        if [ -n "$ST" ] && [ "$ST" != "$STATE_SEEN" ]; then
          STATE_SEEN="$ST"
          echo "    模型状态：$ST"
        fi
        case "$RESP" in
          *'"state":"READY"'*'"usable":true'*) MODEL_READY=yes; break ;;
        esac
        ;;
    esac
    i=$((i + 1))
  done
  kill $SRV2 2>/dev/null; wait $SRV2 2>/dev/null
  if [ -n "$MODEL_READY" ]; then
    echo "    ok  首次启动自动把随附模型装好了（空 home → 状态变成「可用」），全程不用终端"
  else
    echo "    ng  首次启动没有自动装好模型（最后状态：$STATE_SEEN）"
    sed 's/^/      /' "$WS/serve-auto.log"; fail=1
  fi
fi
rm_rf "$WS"

if [ "${SKIP_PKG:-}" = "1" ]; then
  echo ""
  echo "SKIP_PKG=1，跳过 .pkg"
  exit $fail
fi

echo "== 8/9 jpackage 打核心 .pkg（随程序分发已签名模型包）=="
rm_rf "$PKGOUT"
STAGE="$(mktemp -d)"
cp "$ROOT/target/$JAR_NAME" "$STAGE/"
stage_macos_onnx_natives "$STAGE" || { rm_rf "$STAGE"; exit 1; }
# 随附模型包也进 .pkg：用户装完 .pkg，打开应用就自动把模型装好。
stage_model_package "$STAGE"
"$JAVA_HOME/bin/jpackage" --type pkg \
  --name "$APP_NAME" --app-version "$APP_VERSION" \
  --input "$STAGE" --main-jar "$JAR_NAME" --main-class "$MAIN_CLASS" \
  --runtime-image "$RT" --dest "$PKGOUT" --resource-dir "$RESOURCE_STAGE" \
  --java-options "$JVM_ARGS" || { echo "jpackage pkg 失败"; rm_rf "$STAGE"; exit 1; }
rm_rf "$STAGE"
ls -1 "$PKGOUT"
PKG_COUNT="$(find "$PKGOUT" -maxdepth 1 -type f -name '*.pkg' | wc -l | tr -d ' ')"
[ "$PKG_COUNT" = "1" ] || { echo "X 预期恰好一个 .pkg，实际 $PKG_COUNT 个" >&2; exit 1; }
PKG_FILE="$(find "$PKGOUT" -maxdepth 1 -type f -name '*.pkg' -print -quit)"
# .pkg 的签名状态要**单独查**：
#   jpackage --type pkg 会**重新造一个 .app** 放进包里，
#   所以第 6 步给 app-image 的签名**不会**带进 .pkg。
#   「app 签过了」不等于「pkg 里的 app 签过了」——这里直接查，不靠推断。
PKG_SIGN_AUTHORITY=""
if [ -f "$PKG_FILE" ]; then
  PKG_SIGN_AUTHORITY="$(pkgutil --check-signature "$PKG_FILE" 2>&1 | sed -n 's/^Authority=//p' | head -1)"
  if [ -n "$PKG_SIGN_AUTHORITY" ]; then
    echo "    .pkg 签名身份：${PKG_SIGN_AUTHORITY}"
  else
    echo "    .pkg 未签名（没有 Developer ID Installer 身份）→ 不能对外分发"
  fi
fi
PKG_EXPAND="$(mktemp -d)"
if pkgutil --expand-full "$PKG_FILE" "$PKG_EXPAND/content" >/dev/null 2>&1; then
  PKG_PLIST="$(find "$PKG_EXPAND/content" -path "*/$APP_NAME.app/Contents/Info.plist" -print -quit)"
  PKG_MIN="$(/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$PKG_PLIST" 2>/dev/null || true)"
  if [ "$PKG_MIN" != "$MACOS_MIN_VERSION" ]; then
    echo "X .pkg 内应用最低系统是 ${PKG_MIN:-缺失}，应为 $MACOS_MIN_VERSION" >&2
    fail=1
  else
    echo "    .pkg 内应用最低系统：$PKG_MIN"
  fi
  PKG_APP="$(find "$PKG_EXPAND/content" -type d -name "$APP_NAME.app" -print -quit)"
  if [ -z "$PKG_APP" ] || ! assert_sealed_onnx_natives "$PKG_APP"; then
    echo "X .pkg 内应用缺少固定 ORT 原生库布局" >&2
    fail=1
  fi
else
  echo "X 无法展开 .pkg 核对最低系统" >&2
  fail=1
fi
rm_rf "$PKG_EXPAND"
PKG_BYTES=$(bytes "$PKGOUT")
echo "    合计 $PKG_BYTES B = $(mib "$PKG_BYTES") MiB"

# ---- 体积报告：安装包 / 模型 / 安装后，分开报 ----------------------------
#
# 【为什么要分开报三个数】
#   体积闸门管的是「**核心包** ≤ 90 MiB」，那是发布件（app-image）的口径。
#   而用户关心的是另外三个数，它们**互相不等**：
#     · 安装包（.pkg / .dmg）—— 用户要下载的东西；
#     · 模型 —— 随程序分发的识别模型多大；
#     · 安装后占用 —— 装到机器上之后实际占多少磁盘。
#   只报一个「合计」的话，用户既不知道下载要多少流量，也不知道装完要腾多少空间。
# ---- 第 9 步：.dmg（拖拽安装形态）----
#
# 为什么放在这个脚本里，而不是让它自己跑：
#   .dmg 是**从刚打出来并签过名的那个 .app** 做的。
#   要是分开跑，就可能出现「.dmg 是拿上一次遗留的 .app 打的」——
#   产物看起来对，内容却是旧的，而这没有任何东西会喊。
#   → 这里把 APP 显式传进去，来源只有这一处。
if [ "${SKIP_DMG:-}" = "1" ]; then
  echo ""
  echo "SKIP_DMG=1，跳过 .dmg"
else
  echo ""
  echo "== 9/9 打 .dmg（拖拽安装形态：解包即用、不需要授权）=="
  if APP="$APP" bash "$ROOT/tools/package-dmg.sh"; then
    echo "    .dmg 已产出并验证通过"
  else
    echo "!! .dmg 打出或验证失败 → 整个打包判失败"
    fail=1
  fi
fi

echo ""
echo "== 体积报告（安装包 / 模型 / 安装后）=="
DMG_BYTES=0
if [ "${SKIP_DMG:-}" != "1" ] && [ -d "$ROOT/target/dmg" ]; then
  DMG_BYTES=$(bytes "$ROOT/target/dmg")
fi
MODEL_BYTES=0
if [ -n "$MODEL_PACKAGE_DIR" ] && [ -d "$MODEL_PACKAGE_DIR" ]; then
  MODEL_BYTES=$(bytes "$MODEL_PACKAGE_DIR")
fi
APP_BYTES=$(bytes "$APP")
echo "    安装包（.pkg）         = $PKG_BYTES B = $(mib "$PKG_BYTES") MiB"
echo "    安装包（.dmg）         = $DMG_BYTES B = $(mib "$DMG_BYTES") MiB"
if [ "$MODEL_BYTES" -gt 0 ]; then
  echo "    模型（随程序分发）     = $MODEL_BYTES B = $(mib "$MODEL_BYTES") MiB"
else
  echo "    模型（随程序分发）     = 未随包分发"
fi
echo "    安装后占用（.app 实测）= $APP_BYTES B = $(mib "$APP_BYTES") MiB"

# ---- 落盘「包操作记录」----
# stdout 会滚走；交付时要能拿出**这一份**证据：
# 包在哪、什么架构、装了多大、带没带模型、验证过没有。
# 三个产品（脱敏工具 / 律所桌面 / iPhone）的记录格式保持一致，
# 否则交接时得靠人把三份不同格式的东西拼起来看。
RECORD_DIR="$ROOT/target/macos-release"
mkdir -p "$RECORD_DIR"
cat > "$RECORD_DIR/PACKAGE-MACOS.json" <<RECORDEOF
{
  "generatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "product": "LightMeter",
  "platform": "darwin",
  "architecture": "$HOST_ARCH",
  "minimumMacOS": "$MACOS_MIN_VERSION",
  "appVersion": "$APP_VERSION",
  "application": "$ROOT/target/app-image/$APP_NAME.app",
  "packageDirectory": "$PKGOUT",
  "diskImageDirectory": "$ROOT/target/dmg",
  "modelPackage": $([ -n "$MODEL_PACKAGE_DIR" ] && echo "\"$MODEL_PACKAGE_DIR\"" || echo null),
  "bytes": {
    "app": $APP_BYTES,
    "pkg": $PKG_BYTES,
    "dmg": $DMG_BYTES,
    "model": $MODEL_BYTES
  },
  "appSigningAuthority": "${APP_AUTHORITY:-unknown}",
  "pkgSigningAuthority": "${PKG_SIGN_AUTHORITY:-unknown}",
  "adHocSigned": $([ "${APP_AUTHORITY:-}" = "ad-hoc" ] && echo true || echo false),
  "developerSigned": $([ -n "${SIGN_IDENTITY:-}" ] && echo true || echo false),
  "notarized": false,
  "productRoundTripVerified": $([ "$fail" -eq 0 ] && echo true || echo false),
  "releaseLevel": "candidate-not-for-distribution",
  "nextStep": "需要对外分发时执行 tools/package-macos-sign-notarize.sh（Developer ID 签名 + 公证 + 装订 + 验证）"
}
RECORDEOF
echo "    包操作记录：$RECORD_DIR/PACKAGE-MACOS.json"

if [ "$fail" -ne 0 ]; then
  echo "X 打包完成但**验证没过**，不要发这个包"
  exit 1
fi

# 法务桌面端只接受能绑定到 clean HEAD 与精确二进制摘要的回执。
SOURCE_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
if [ "${#SOURCE_COMMIT}" -ne 40 ] || [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=normal)" ]; then
  echo "X 构建回执要求完整 40 位 clean HEAD；当前源码有未提交内容，拒绝生成候选回执" >&2
  exit 1
fi
DMG_FILE="$ROOT/target/dmg/$APP_NAME.dmg"
RECEIPT="$RECORD_DIR/BUILD-RECEIPT.json"
python3 - "$SOURCE_COMMIT" "$APP/Contents/app/$JAR_NAME" "$PKG_FILE" "$DMG_FILE" \
  "$APP/Contents/app/native/onnxruntime" "$RECEIPT" <<'PY'
import datetime, hashlib, json, pathlib, sys
commit, jar, pkg, dmg, native_dir, receipt = sys.argv[1:]
def info(kind, path):
    p = pathlib.Path(path)
    if not p.is_file():
        return None
    data = p.read_bytes()
    return {"kind": kind, "fileName": p.name, "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest()}
def native(name):
    p = pathlib.Path(native_dir, name)
    data = p.read_bytes()
    return {"file": "Contents/app/native/onnxruntime/" + name,
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
jar_data = pathlib.Path(jar).read_bytes()
app_data_dir = pathlib.Path(jar).parent
payload = {
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "sourceCommit": commit,
    "workingTreeDirty": False,
    "applicationJarSha256": hashlib.sha256(jar_data).hexdigest(),
    "artifacts": [x for x in (info("dmg", dmg), info("pkg", pkg)) if x],
    "modelPackagePresent": any((app_data_dir / "models/ner-package").glob("*.zip")),
    "expandedModelPresent": (app_data_dir / "models/ner/manifest.json").is_file(),
    "sealedOnnxNativeRelativeDir": "Contents/app/native/onnxruntime",
    "sealedOnnxNativeLibraries": [native("libonnxruntime.dylib"),
                                  native("libonnxruntime4j_jni.dylib")],
}
pathlib.Path(receipt).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8")
PY
echo "    clean-source 构建回执：$RECEIPT"

echo ""
echo "PASS 打包并验证通过"
