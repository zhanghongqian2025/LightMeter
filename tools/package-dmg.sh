#!/usr/bin/env bash
# M4-macOS：把 .app 打成 .dmg（拖拽安装形态），并**真的把它挂载跑一遍**验证。
#
# 【为什么 .pkg 之外还要 .dmg】
#   .pkg 走「安装器」路线：装到系统位置，需要授权，卸载要走 installer。
#   .dmg 走「拖拽」路线：解包即用、不需要授权、删除就是拖到废纸篓。
#   两者面向的人不同：.pkg 适合 IT 统一分发，.dmg 适合律所同事自己装。
#   路线图 G5 明确列了「macOS .dmg」，本脚本交付它。
#
# 【验证的取向：退出码不算证据，外部行为才算】
#   hdiutil 退出 0 不等于用户能用。必须真的：
#     ① hdiutil verify —— 镜像本身没坏；
#     ② 只读挂载（-nobrowse）后确认 .app 真的在里面；
#     ③ 对**挂载出来的** .app 做 codesign --verify --deep --strict；
#     ④ **从挂载卷里真的跑一次脱敏 + 一次还原**（往返逐字一致）。
#   ④ 是关键：DMG 里的 app 跑在**只读卷**上、路径也跟平时不一样，
#   这两件事都可能让它起不来，而任何「打包成功」的检查都发现不了。
#
# 【为什么验证时隔离 user.home】
#   加载器依次找 <程序目录>/models/ner 和 ~/.litemask/models/ner。
#   开发机装了模型 → 不隔离就测的是「装了模型」那一态，
#   而 DMG 发给用户时**里面没有模型**（方案 B：模型包是独立签名的 zip）。
#   → 隔离成一个空 home，测的才是**新用户拿到 DMG 时的真实形态**：
#     规则类（身份证 / 手机号）照常脱敏，人名与机构名**明说没脱**。
#   这一态必须验：它正是「用户以为脱干净了，其实没脱」最容易发生的形态。
#
# 用法：
#   ./tools/package-dmg.sh                 # 用 target/app-image/LightMeter.app
#   APP=<别的.app> ./tools/package-dmg.sh  # 指定别的 .app
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/tools/shell-lib.sh"
APP_NAME=LightMeter
APP="${APP:-$ROOT/target/app-image/$APP_NAME.app}"
DMG_OUT="${DMG_OUT:-$ROOT/target/dmg}"
DMG="$DMG_OUT/$APP_NAME.dmg"
VOLNAME="$APP_NAME"

mib() { awk -v b="$1" 'BEGIN{printf "%.2f", b/1048576}'; }

if [ ! -d "$APP" ]; then
  echo "!! 找不到 .app：${APP}"
  echo "   先跑 tools/package-macos.sh（它会打出 .app），或用 APP=<路径> 指定。"
  exit 1
fi

STAGE="$(mktemp -d)"
MNT="$(mktemp -d)"
MOUNTED=0
fail=0
cleanup() {
  if [ "$MOUNTED" = "1" ]; then
    hdiutil detach "$MNT" -quiet >/dev/null 2>&1 || true
  fi
  rm_rf "$STAGE"; rm_rf "$MNT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== 1/5 准备镜像内容（.app + Applications 软链接）=="
# ditto 而不是 cp -R：它是 macOS 自己的拷贝工具，会带上扩展属性与资源分支。
# jpackage 打出来的 .app 带代码签名，而签名对这些内容是敏感的——
# 用不保留元数据的拷贝方式，拷完的 app 会 codesign 校验失败。
ditto "$APP" "$STAGE/$APP_NAME.app"
ln -s /Applications "$STAGE/Applications"
echo "    源 .app = $(stat -f%z "$APP/Contents/MacOS/$APP_NAME") B（可执行文件）"
echo "    待装入：${APP_NAME}.app + Applications（拖拽安装的常规形态）"

echo "== 2/5 hdiutil 打 .dmg（UDZO 压缩、只读）=="
mkdir -p "$DMG_OUT"
rm -f "$DMG"
# hdiutil create 在新系统上会打一行「已废弃，建议用 diskutil image create」的警告。
# 它是**警告**不是错误，功能仍在；换成 diskutil 会让脚本绑死在较新的 macOS 上，
# 所以这里保留 hdiutil，并把警告原样留在输出里，不藏。
if ! hdiutil create -srcfolder "$STAGE" -volname "$VOLNAME" \
     -fs HFS+ -format UDZO -ov "$DMG"; then
  echo "!! hdiutil create 失败"
  exit 1
fi
DMG_BYTES=$(stat -f%z "$DMG")
echo "    ${APP_NAME}.dmg = ${DMG_BYTES} B = $(mib "$DMG_BYTES") MiB"

echo "== 3/5 校验镜像 + 只读挂载 =="
if ! hdiutil verify "$DMG"; then
  echo "!! hdiutil verify 没过——镜像本身是坏的"
  fail=1
fi
if ! hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MNT"; then
  echo "!! 挂载失败"
  exit 1
fi
MOUNTED=1
MAP="$MNT/$APP_NAME.app"
if [ ! -d "$MAP" ]; then
  echo "!! 挂载卷里没有 ${APP_NAME}.app"
  ls -1 "$MNT" | sed 's/^/      /'
  fail=1
fi

echo "== 4/5 验挂载出来的 .app：签名 + 真的脱敏一次 + 真的还原一次 =="
if [ "$fail" -eq 0 ]; then
  if codesign --verify --deep --strict "$MAP" 2>"$STAGE/codesign.err"; then
    echo "    ok  挂载卷里的 .app 签名校验通过"
  else
    echo "!! 挂载卷里的 .app 签名校验失败："
    sed 's/^/      /' "$STAGE/codesign.err"
    fail=1
  fi
fi

if [ "$fail" -eq 0 ]; then
  WS="$(mktemp -d)"
  EMPTY_HOME="$WS/empty-home"
  mkdir -p "$EMPTY_HOME"
  # 隔离 user.home：这一步测的是**新用户形态**（DMG 里没有模型包）。
  export JAVA_TOOL_OPTIONS="-Duser.home=${EMPTY_HOME}"
  printf '张三丰的身份证是11010519491231002X，手机13800138000。\n' > "$WS/t.txt"
  if "$MAP/Contents/MacOS/$APP_NAME" redact "$WS/t.txt" > "$WS/redact.log" 2>&1; then
    OUT="$WS/t.redacted.txt"
    MAPF="$WS/t.txt.redacted.map.json"
    BACK="$WS/t.redacted.restored.txt"
    if [ ! -f "$OUT" ]; then
      echo "!! 产物名不对：期望 ${OUT}，实际有：$(ls -1 "$WS" | tr '\n' ' ')"
      fail=1
    elif ! grep -q '<身份证1>' "$OUT"; then
      echo "!! 脱敏件里没有 <身份证1>（规则类没生效）"
      sed 's/^/      /' "$WS/redact.log"
      fail=1
    elif ! grep -q '<手机号1>' "$OUT"; then
      echo "!! 脱敏件里没有 <手机号1>（规则类没生效）"
      sed 's/^/      /' "$WS/redact.log"
      fail=1
    elif grep -q '<人名1>' "$OUT"; then
      # 这一条**不是**「多脱敏了」的报错，而是「声明与事实不符」的报错：
      # 人名只能靠 NER 模型脱，而这个 DMG 里**没有模型包**（方案 B）。
      # 出现了 <人名1> 说明模型是从别处（开发机的 ~/.litemask）捡来的，
      # 隔离没生效——那样测的就不是「新用户形态」了。
      echo "!! 隔离没生效：没模型的 DMG 里却产出了 <人名1>（模型是从开发机捡来的）"
      fail=1
    elif ! grep -qE '没有脱敏|未启用' "$WS/redact.log"; then
      echo "!! 没装模型却没有声明「人名与机构名没有被脱敏」——用户会以为脱干净了"
      sed 's/^/      /' "$WS/redact.log"
      fail=1
    else
      LEAK=""
      for raw in '11010519491231002X' '13800138000'; do
        if grep -q "$raw" "$OUT"; then LEAK="$LEAK $raw"; fi
      done
      # 碎片也要查：把 18 位身份证切成三条命中时，每条都不等于原文，
      # 正则查不出来，但页面上就只剩读不出是不是敏感信息的东西。
      if printf '%s' "$(sed 's/<[^>]*>//g' "$OUT")" | grep -qE '[0-9]{6,}'; then
        LEAK="${LEAK} 连续6位以上裸数字"
      fi
      if [ -n "$LEAK" ]; then
        echo "!! 脱敏件里仍有原文或碎片：${LEAK}"
        sed 's/^/      /' "$OUT"
        fail=1
      elif [ ! -f "$MAPF" ]; then
        echo "!! 映射文件没生成：${MAPF}"
        fail=1
      elif ! grep -q '11010519491231002X' "$MAPF" || ! grep -q '13800138000' "$MAPF"; then
        echo "!! 映射文件里没有完整的原值（身份证 / 手机号必须都在）"
        sed 's/^/      /' "$MAPF"
        fail=1
      else
        # 【红灯原因必须精确：三种失败分开报】
        #   还原**命令失败** / 还原件**没生成** / 还原件**内容不一致**是三件事。
        #   合成一个 `||` 条件的话，失败且残留着旧文件时会报成「往返不一致」——
        #   而真实原因是「还原命令退了非 0」，指向的排查方向完全不同。
        RESTORE_RC=0
        "$MAP/Contents/MacOS/$APP_NAME" restore "$OUT" --map "$MAPF" \
          > "$WS/restore.log" 2>&1 || RESTORE_RC=$?
        if [ "$RESTORE_RC" -ne 0 ]; then
          echo "!! 从挂载卷里还原失败（退出码 ${RESTORE_RC}）"
          sed 's/^/      /' "$WS/restore.log"
          echo "      期望的还原件：${BACK}"
          fail=1
        elif [ ! -f "$BACK" ]; then
          echo "!! 还原件没生成：${BACK}"
          sed 's/^/      /' "$WS/restore.log"
          fail=1
        elif ! cmp -s "$WS/t.txt" "$BACK"; then
          echo "!! 往返不一致：还原件与原文不逐字相同"
          diff "$WS/t.txt" "$BACK" 2>/dev/null | sed 's/^/      /' | head -10
          fail=1
        else
          echo "    ok  只读卷里跑得起来：脱敏正常、规则类生效、原文零残留、映射完整、往返逐字一致"
          echo "    ok  且如实声明了「没模型 → 人名与机构名没被脱敏」（新用户形态）"
        fi
      fi
    fi
  else
    echo "!! 挂载卷里的 CLI 跑脱敏失败："
    sed 's/^/      /' "$WS/redact.log"
    fail=1
  fi
  unset JAVA_TOOL_OPTIONS
  rm_rf "$WS" >/dev/null 2>&1 || true
fi

echo "== 5/5 卸载镜像卷 =="
if [ "$MOUNTED" = "1" ]; then
  if hdiutil detach "$MNT" -quiet; then
    echo "    已卸载"
  else
    echo "!! 卸载失败（卷可能还挂着）"
    fail=1
  fi
  MOUNTED=0
fi

echo ""
if [ "$fail" -ne 0 ]; then
  echo "X .dmg 打出来了但**验证没过**，不要发这个文件"
  exit 1
fi
echo "PASS .dmg 打出并验证通过：${DMG}"
