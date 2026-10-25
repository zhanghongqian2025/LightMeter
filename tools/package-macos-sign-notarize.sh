#!/usr/bin/env bash
#
# macOS 分发级签名 + 公证 + 分发包验证（LightMeter 脱敏工具）。
#
# 【为什么单独一个脚本，而不是塞进 package-macos.sh】
#   package-macos.sh 负责「把东西造出来」（含 ad-hoc 签名，跑得起来即可）；
#   本脚本负责「让它能被普通用户装上」——那需要 **Developer ID 签名 + 公证 + 装订**，
#   两者需要的凭据、失败模式与产物完全不同，混在一起会让「造不出来」和「签不出去」互相掩盖。
#
# 【为什么必须 inside-out 签名】
#   jpackage 产出的 .app 里有嵌套内容：Contents/runtime（整棵 JRE）、
#   Contents/app/*.jar、Contents/app/**/*.dylib。
#   macOS 要求**先签内层、再签外层**；只签最外层的话，
#   codesign --verify --deep 会失败，而 Gatekeeper 报的是「应用已损坏」——
#   听起来像产品坏了，真因是签名顺序。
#
# 【公证的硬性前提】
#   1) 应用用 **Developer ID Application** 身份签，且带 hardened runtime
#      （--options runtime）；Java 应用还要 tools/macos-runtime.entitlements
#      里的三项，否则 JVM 的 JIT 起不来。
#   2) .pkg 用 **Developer ID Installer** 身份签（pkgbuild --sign）。
#   3) 提交 notarytool 后要 **staple**（装订），否则离线安装的机器仍会被拦。
#   凭据缺失时本脚本**明确报告缺什么**，并产出 ad-hoc 候选包（可内部用，不可对外发）。
#
# 用法：
#   LITEMASK_SIGN_IDENTITY="Developer ID Application: X (TEAMID)" \
#   LITEMASK_INSTALLER_IDENTITY="Developer ID Installer: X (TEAMID)" \
#   LITEMASK_NOTARY_PROFILE=my-notary-profile \
#     ./tools/package-macos-sign-notarize.sh
#
#   也可指定产物位置：
#     APP=target/app-image/LightMeter.app ./tools/package-macos-sign-notarize.sh
#
# 退出码：0 全部通过；1 有失败项。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${APP:-$ROOT/target/app-image/LightMeter.app}"
OUT="${OUT:-$ROOT/target/macos-release}"
ENTITLEMENTS="${LITEMASK_ENTITLEMENTS:-$ROOT/tools/macos-runtime.entitlements}"
IDENTITY="${LITEMASK_SIGN_IDENTITY:-}"
INSTALLER_IDENTITY="${LITEMASK_INSTALLER_IDENTITY:-}"
NOTARY_PROFILE="${LITEMASK_NOTARY_PROFILE:-}"
NOTARY_KEY="${LITEMASK_NOTARY_KEY:-}"
NOTARY_KEY_ID="${LITEMASK_NOTARY_KEY_ID:-}"
NOTARY_ISSUER="${LITEMASK_NOTARY_ISSUER:-}"

FAIL=0
REPORT="$(mktemp -t litemask-macos-sign)"
note() { printf '  %s\n' "$*"; }
ok()   { printf '  ✔ %s\n' "$*"; }
bad()  { printf '  ✘ %s\n' "$*"; FAIL=$((FAIL + 1)); }

if [ "$(uname -s)" != "Darwin" ]; then
  echo "X 本脚本必须在 macOS 上运行" >&2
  exit 1
fi
if [ ! -d "$APP" ]; then
  echo "X 找不到应用包：$APP" >&2
  echo "  解除办法：先跑 ./tools/package-macos.sh（或用 APP=... 指定已打好的 .app）" >&2
  exit 1
fi
mkdir -p "$OUT"

# ---------------------------------------------------------------- 1. 身份
echo "== 1. 签名身份 =="
if [ -z "$IDENTITY" ]; then
  FOUND="$(security find-identity -v -p codesigning 2>/dev/null | grep 'Developer ID Application:' | head -1 || true)"
  if [ -n "$FOUND" ]; then
    IDENTITY="$(printf '%s' "$FOUND" | sed -n 's/.*"\(.*\)".*/\1/p')"
    ok "自动选中：$IDENTITY"
  else
    IDENTITY="-"
    bad "钥匙串里没有 Developer ID Application 身份"
    note "解除办法：由发布主体创建 Developer ID Application 证书并导入钥匙串；"
    note "          或设置 LITEMASK_SIGN_IDENTITY=\"Developer ID Application: 名称 (TEAMID)\""
  fi
else
  ok "使用指定身份：$IDENTITY"
fi

if [ -z "$INSTALLER_IDENTITY" ]; then
  FOUND_I="$(security find-identity -v -p codesigning 2>/dev/null | grep 'Developer ID Installer:' | head -1 || true)"
  if [ -n "$FOUND_I" ]; then
    INSTALLER_IDENTITY="$(printf '%s' "$FOUND_I" | sed -n 's/.*"\(.*\)".*/\1/p')"
    ok "自动选中安装器身份：$INSTALLER_IDENTITY"
  else
    INSTALLER_IDENTITY=""
    bad "钥匙串里没有 Developer ID Installer 身份（.pkg 无法做分发级签名）"
    note "解除办法：由发布主体创建 Developer ID Installer 证书并导入钥匙串"
  fi
else
  ok "使用指定安装器身份：$INSTALLER_IDENTITY"
fi

# ---------------------------------------------------------------- 2. inside-out 签名
echo ""
echo "== 2. inside-out 签名 =="
HARDENED=()
if [ "$IDENTITY" != "-" ]; then
  HARDENED=(--options runtime --timestamp)
fi
if [ -f "$ENTITLEMENTS" ]; then
  note "使用 entitlements：$ENTITLEMENTS"
else
  bad "缺少 entitlements：$ENTITLEMENTS"
  note "解除办法：Java 应用在 hardened runtime 下没有这三项，JVM 的 JIT 起不来"
fi

# 先签内层：嵌套 bundle 与 Mach-O 可执行文件
#
# 【为什么不能用扩展名筛】
#   扩展名筛选会漏掉 **JRE 里没有扩展名的可执行文件**：
#   Contents/runtime/Contents/Home/bin/java 就是其中之一。
#   它是 Mach-O，但既不是 *.dylib 也不是 *.app —— 一旦漏签，
#   notarytool 会以「code object is not signed at all」拒收，
#   报错指向公证服务，而真因是**签名名单少了一项**。
#   → 改成用 `file -b` 逐个判定「是不是 Mach-O」，认的是格式而不是名字。
COUNT=0
while IFS= read -r item; do
  [ "$item" = "$APP" ] && continue
  case "$item" in
    *"/_CodeSignature/"*|*"/CodeResources") continue ;;
  esac
  if codesign --force --sign "$IDENTITY" "${HARDENED[@]}" "$item" 2>>"$REPORT.log"; then
    COUNT=$((COUNT + 1))
  else
    bad "嵌套签名失败：${item#$APP/}"
    sed 's/^/      /' "$REPORT.log" | tail -5
  fi
done < <(
  find "$APP" -type f 2>/dev/null | sort | while IFS= read -r f; do
    case "$f" in
      *"/_CodeSignature/"*) continue ;;
    esac
    if file -b "$f" 2>/dev/null | grep -q 'Mach-O'; then printf '%s\n' "$f"; fi
  done
  # 嵌套 bundle（.framework / .app / .bundle）本身也要签，且要排在里面的文件之后
  find "$APP" \( -name '*.framework' -o -name '*.app' -o -name '*.bundle' \) -type d 2>/dev/null | sort -r
)
note "已签嵌套项：$COUNT"

if codesign --force --sign "$IDENTITY" "${HARDENED[@]}" ${ENTITLEMENTS:+--entitlements "$ENTITLEMENTS"} "$APP" 2>>"$REPORT.log"; then
  ok "应用签名完成"
else
  bad "应用签名失败"
  sed 's/^/      /' "$REPORT.log" | tail -10
fi
if codesign --verify --deep --strict --verbose=2 "$APP" 2>&1 | sed 's/^/      /'; then
  ok "codesign --verify --deep --strict 通过"
else
  bad "codesign 校验未通过"
fi

# ---------------------------------------------------------------- 3. 打包
echo ""
echo "== 3. .pkg 与 .dmg =="
APP_NAME="$(basename "$APP" .app)"
PKG="$OUT/$APP_NAME.pkg"
# 安装器包的标识符要从**打好的 .app** 里读真实 bundle id，不猜：
# 猜错的后果是包升级时被当成另一个产品（与 MSI 1638 同类的问题）。
PKG_IDENTIFIER="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Contents/Info.plist" 2>/dev/null || true)"
[ -z "$PKG_IDENTIFIER" ] && PKG_IDENTIFIER="com.litemask.desens"
DMG="$OUT/$APP_NAME.dmg"
rm -rf "$OUT/pkgroot" "$OUT/dmgroot"
mkdir -p "$OUT/pkgroot" "$OUT/dmgroot"
cp -R "$APP" "$OUT/pkgroot/"
if [ -n "$INSTALLER_IDENTITY" ]; then
  if pkgbuild --root "$OUT/pkgroot" --install-location /Applications \
      --identifier "$PKG_IDENTIFIER" --sign "$INSTALLER_IDENTITY" "$PKG" 2>>"$REPORT.log"; then
    ok "已签 .pkg：$PKG（标识符 $PKG_IDENTIFIER）"
  else
    bad "pkgbuild 失败"
    sed 's/^/      /' "$REPORT.log" | tail -10
  fi
else
  if pkgbuild --root "$OUT/pkgroot" --install-location /Applications \
      --identifier "$PKG_IDENTIFIER" "$PKG" 2>>"$REPORT.log"; then
    note "已出未签 .pkg：$PKG（无 Developer ID Installer，不能对外分发）"
  else
    bad "pkgbuild 失败"
  fi
fi
cp -R "$APP" "$OUT/dmgroot/"
ln -sf /Applications "$OUT/dmgroot/Applications" 2>/dev/null || true
rm -f "$DMG"
if hdiutil create -volname "$APP_NAME" -srcfolder "$OUT/dmgroot" -ov -format UDZO "$DMG" >/dev/null 2>>"$REPORT.log"; then
  ok "已出 .dmg：$DMG"
else
  bad "hdiutil create 失败"
fi

# ---------------------------------------------------------------- 4. 公证
echo ""
echo "== 4. 公证（notarytool） =="
NOTARY_TARGET=""
if [ -f "$DMG" ]; then NOTARY_TARGET="$DMG"; elif [ -f "$PKG" ]; then NOTARY_TARGET="$PKG"; fi
NOTARIZED=0
if [ -z "$NOTARY_PROFILE" ] && { [ -z "$NOTARY_KEY" ] || [ -z "$NOTARY_KEY_ID" ] || [ -z "$NOTARY_ISSUER" ]; }; then
  bad "缺少公证凭据"
  note "解除办法：xcrun notarytool store-credentials <profile> 存一次；"
  note "          或设置 LITEMASK_NOTARY_KEY / LITEMASK_NOTARY_KEY_ID / LITEMASK_NOTARY_ISSUER"
  note "          （凭据留在受控环境，不入库）"
elif [ -z "$NOTARY_TARGET" ]; then
  bad "没有可提交的产物"
else
  if [ -n "$NOTARY_PROFILE" ]; then
    SUBMIT_ARGS=(submit "$NOTARY_TARGET" --wait --keychain-profile "$NOTARY_PROFILE")
  else
    SUBMIT_ARGS=(submit "$NOTARY_TARGET" --wait --key "$NOTARY_KEY" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER")
  fi
  note "提交公证（这一步可能要几分钟）…"
  if xcrun notarytool "${SUBMIT_ARGS[@]}" 2>&1 | sed 's/^/      /' | tail -20; then
    ok "公证已通过"
    NOTARIZED=1
    if xcrun stapler staple "$NOTARY_TARGET" 2>&1 | sed 's/^/      /'; then
      ok "已装订（staple）"
    else
      bad "staple 失败：离线安装的机器仍会被 Gatekeeper 拦"
    fi
    if [ -n "$PKG" ] && [ -f "$PKG" ] && [ "$NOTARY_TARGET" = "$DMG" ]; then
      xcrun stapler staple "$PKG" >/dev/null 2>&1 && ok "已给 .pkg 装订" || note ".pkg 未装订"
    fi
  else
    bad "公证未通过"
  fi
fi

# ---------------------------------------------------------------- 5. 验证
echo ""
echo "== 5. 分发包验证 =="
SPCTL_APP=1
spctl --assess --type execute --verbose=2 "$APP" 2>&1 | sed 's/^/      /' || SPCTL_APP=0
if [ "$SPCTL_APP" -eq 1 ]; then ok "spctl 认可 .app"; else bad "spctl 不认可 .app（未签名/未公证时属于预期）"; fi
if [ -f "$PKG" ]; then
  SPCTL_PKG=1
  spctl --assess --type install --verbose=2 "$PKG" 2>&1 | sed 's/^/      /' || SPCTL_PKG=0
  if [ "$SPCTL_PKG" -eq 1 ]; then ok "spctl 认可 .pkg"; else bad "spctl 不认可 .pkg"; fi
fi
if [ "$NOTARIZED" -eq 1 ]; then
  xcrun stapler validate "$NOTARY_TARGET" 2>&1 | sed 's/^/      /' && ok "stapler 校验通过" || bad "stapler 校验未通过"
fi
hdiutil verify "$DMG" 2>&1 | tail -2 | sed 's/^/      /'

# ---------------------------------------------------------------- 6. 报告
echo ""
echo "== 6. 产物 =="
SHA_APP="$(shasum -a 256 "$APP/Contents/MacOS/$APP_NAME" 2>/dev/null | awk '{print $1}')"
SHA_DMG=""
[ -f "$DMG" ] && SHA_DMG="$(shasum -a 256 "$DMG" | awk '{print $1}')"
SHA_PKG=""
[ -f "$PKG" ] && SHA_PKG="$(shasum -a 256 "$PKG" | awk '{print $1}')"
printf '{"app":"%s","pkg":"%s","dmg":"%s","architecture":"%s","developerSigned":%s,"installerSigned":%s,"notarized":%s,"sha256":{"appBinary":"%s","pkg":"%s","dmg":"%s"}}\n' \
  "$APP" "$PKG" "$DMG" "$(uname -m)" \
  "$([ "$IDENTITY" != "-" ] && echo true || echo false)" \
  "$([ -n "$INSTALLER_IDENTITY" ] && echo true || echo false)" \
  "$([ "$NOTARIZED" -eq 1 ] && echo true || echo false)" \
  "$SHA_APP" "$SHA_PKG" "$SHA_DMG"

# ---- 落盘「包操作记录」----
# 为什么必须落盘：stdout 会滚走，交付时要能拿出**这一份**证据
# （包在哪、什么架构、谁签的、有没有公证、三份 SHA-256、每项验证的结果）。
# 三个产品的记录格式要一致，否则交接时得靠人把三份不同格式的东西拼起来看。
RECORD="$OUT/RELEASE-MACOS.json"
cat > "$RECORD" <<RECORDEOF
{
  "generatedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "product": "LightMeter",
  "platform": "darwin",
  "hostArchitecture": "$(uname -m)",
  "application": "$APP",
  "package": "$PKG",
  "diskImage": "$DMG",
  "developerSigned": $([ "$IDENTITY" != "-" ] && echo true || echo false),
  "signingIdentity": $([ "$IDENTITY" != "-" ] && echo "\"$IDENTITY\"" || echo null),
  "installerSigned": $([ -n "$INSTALLER_IDENTITY" ] && echo true || echo false),
  "notarized": $([ "$NOTARIZED" -eq 1 ] && echo true || echo false),
  "stapled": $([ "$NOTARIZED" -eq 1 ] && echo true || echo false),
  "sha256": { "pkg": "$SHA_PKG", "dmg": "$SHA_DMG" },
  "releaseLevel": "$([ "$IDENTITY" != "-" ] && [ "$NOTARIZED" -eq 1 ] && echo developer-signed-and-notarized || echo candidate-not-for-distribution)",
  "failures": $FAIL
}
RECORDEOF
note "包操作记录：$RECORD"

echo ""
if [ "$FAIL" -ne 0 ]; then
  echo "X 有 $FAIL 项未通过 —— 上面逐条写了缺什么、怎么补。这个包**不能对外发**。"
  exit 1
fi
if [ "$IDENTITY" = "-" ] || [ "$NOTARIZED" -eq 0 ]; then
  echo "!! 产物是 ad-hoc 候选包（未 Developer ID 签名或未公证）：只能内部试用，不能对外分发。"
  exit 1
fi
echo "PASS 分发级签名 + 公证 + 验证全部通过"
