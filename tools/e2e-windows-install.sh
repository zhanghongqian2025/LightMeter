#!/usr/bin/env bash
# Windows「装完再验」：macOS 侧驱动（块 33）。
#
# 【这一条为什么和其他 e2e 不一样】
#   其他 e2e 都在 macOS 上跑；这一条验的是「msi 装上以后能不能用」，
#   必须**在真 Windows 上**跑（msiexec、Program Files、卸载项全是 Windows 专有）。
#   → 在 macOS 上它做两件事：
#       1) **真跑判定内核的判别力自证**（work/windows-install-checks-test.ps1）——
#          证明「验」那一半的逻辑是对的、不是恒真；
#       2) 报告 SKIP，退出码 77 —— **不是通过**。
#   在 Windows（Git Bash / MSYS / Cygwin）上它会真的调 tools/e2e-windows-install.ps1。
#
# 【为什么「判定内核」这一半要在 macOS 上真跑，而不是直接 SKIP 掉】
#   「装完再验」整件事里，只有「msiexec 装」这一下是平台专有的；
#   「产物对不对」的判定是读文件 + 字面比对，**平台中立**。
#   → 如果整条在 macOS 上只报 SKIP，那「验」这一半在本项目里就**零验证**，
#     而它恰恰是最容易写错、又最容易悄悄退化成恒真的那一半
#     （例如「没给原值就算通过」——那这条判据永远绿）。
#   → 所以判定内核这一半在本机**必须真跑**，且必须带差分式负控制。
#
# 【判据独立于被测实现】
#   判定内核不 import 任何产品代码、不调用产品，只读文件。
#   行为测试里的「现场」是用脚本自己造的占位文件，
#   但**判定函数是从 tools/windows-install-checks.ps1 的语法树里抽出来的**
#   （不是复制一份）—— 脚本改了，测试自动跟着变。
#
# 【退出码】
#   0  = 判定内核在位且判别力自证通过；Windows 真机段**未跑**（见输出）
#   1  = 有断言失败
#   77 = 环境不具备（不是 Windows）—— **不是通过**
#
# 【--checks-only 的含义】
#   只跑**本机可验证的那一半**（判定内核的真解析 + 判别力自证），
#   跑完就退出（0 = 这一半全绿），**不**进入 Windows 真机段。
#   → 收口快项用它把「判定内核的逐条断言」落盘留证。
#   → 注意它**不是**「装完再验整条通过」：Windows 真机段（装/验/卸）**根本没跑**。
#     所以 --checks-only 的 0 只代表「判定内核这一半绿」，输出里会明确写这一点。
set -uo pipefail

CHECKS_ONLY=0
for a in "$@"; do
  case "$a" in
    --checks-only) CHECKS_ONLY=1 ;;
    *) echo "用法：$0 [--checks-only]" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK" 2>/dev/null || true; }
trap cleanup EXIT

# ---- 工具探测 ----
PWSH="${LITEMASK_PWSH:-}"
if [ -z "$PWSH" ]; then
  for cand in /opt/homebrew/opt/powershell/bin/pwsh /usr/local/bin/pwsh \
              /opt/homebrew/bin/pwsh /usr/local/microsoft/powershell/7/pwsh; do
    if [ -x "$cand" ]; then PWSH="$cand"; break; fi
  done
fi
if [ -z "$PWSH" ] && command -v pwsh >/dev/null 2>&1; then
  PWSH="$(command -v pwsh)"
fi

CHECKS="$ROOT/tools/windows-install-checks.ps1"
TEST_PS1="${LITEMASK_INSTALL_CHECKS_TEST:-$ROOT/../work/windows-install-checks-test.ps1}"

echo "== 0. 前置检查 =="
if [ ! -f "$CHECKS" ]; then
  echo "FAIL 找不到判定内核：${CHECKS}"
  exit 1
fi
echo "   判定内核 = ${CHECKS}"
if [ -z "$PWSH" ]; then
  echo "FAIL 找不到真实 PowerShell 解释器（pwsh）——判定内核无法被验证。"
  echo "     这一项是**未覆盖**，不是通过。"
  exit 1
fi
echo "   pwsh = ${PWSH}"

if [ ! -f "$TEST_PS1" ]; then
  echo "FAIL 找不到判定内核的行为测试：${TEST_PS1}"
  exit 1
fi

# =============================================================================
# 1. 判定内核：真解析 + 判别力自证（真解释器执行）
# =============================================================================
echo "== 1. 判定内核：真解析 =="
PARSE_OUT="${WORK}/parse.log"
"$PWSH" -NoProfile -Command "
\$ErrorActionPreference='Stop'
\$tokens=\$null; \$errs=\$null
\$ast=[System.Management.Automation.Language.Parser]::ParseFile('${CHECKS}', [ref]\$tokens, [ref]\$errs)
if(\$errs -and \$errs.Count -gt 0){
  Write-Host ('PARSE_FAIL ' + \$errs.Count + ' 处')
  \$errs | ForEach-Object { Write-Host ('  line ' + \$_.Extent.StartLineNumber + ' : ' + \$_.Message) }
  exit 1
}
Write-Host ('PARSE_OK tokens=' + \$tokens.Count)
" > "$PARSE_OUT" 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  echo "FAIL 判定内核被真实 PowerShell 解析器拒绝："
  sed 's/^/   /' "$PARSE_OUT"
  exit 1
fi
echo "   $(grep -m1 'PARSE_OK' "$PARSE_OUT")"

echo "== 2. 判定内核：判别力自证（正控制 + 差分式负控制）=="
SELFTEST_OUT="${WORK}/selftest.log"
"$PWSH" -NoProfile -File "$TEST_PS1" -ChecksPath "$CHECKS" > "$SELFTEST_OUT" 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  echo "FAIL 判定内核的行为测试没过："
  sed 's/^/   /' "$SELFTEST_OUT"
  exit 1
fi
# 结论必须**读得出来**（不是「退出码 0 就算过」）。
PASSN=$(grep -oE 'PASS=[0-9]+' "$SELFTEST_OUT" | tail -1 | cut -d= -f2)
FAILN=$(grep -oE 'FAIL=[0-9]+' "$SELFTEST_OUT" | tail -1 | cut -d= -f2)
if [ -z "$PASSN" ] || [ -z "$FAILN" ]; then
  echo "FAIL 行为测试的结论读不出来（没有 PASS=/FAIL= 摘要行）——这一项根本没验上"
  sed 's/^/   /' "$SELFTEST_OUT"
  exit 1
fi
if [ "$FAILN" != "0" ]; then
  echo "FAIL 行为测试报红 ${FAILN} 条"
  sed 's/^/   /' "$SELFTEST_OUT"
  exit 1
fi
echo "   行为测试：PASS=${PASSN} FAIL=${FAILN}"
echo "   负控制组数 = $(grep -cE '^  ok  负控制 [0-9]+：' "$SELFTEST_OUT")（每条事实一组）"

# =============================================================================
# 3. Windows 真机段
# =============================================================================
if [ "$CHECKS_ONLY" = "1" ]; then
  echo "== 3. Windows 真机段：按 --checks-only **不跑** =="
  echo "   这一步会真的 msiexec 装 msi、跑装好的程序、再卸载 —— 只能在真 Windows 上做。"
  echo "   本次只验了「判定内核」那一半（上面 ${PASSN} 条断言）。"
  echo "   **装完再验整条**在真机上的结论：**未验证**。"
  exit 0
fi

echo "== 3. Windows 真机段（装 msi → 跑 → 判定 → 卸载）=="
OS="$(uname -s 2>/dev/null || echo unknown)"
case "$OS" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *)
    echo "SKIP 这不是 Windows（uname -s = ${OS}）。"
    echo "     「msi 装完能不能用」必须在真 Windows 上跑："
    echo "     msiexec 装、Program Files 布局、卸载后残留，全是 Windows 专有。"
    echo "     本机已验证的只有「判定内核」那一半（上面 ${PASSN} 条断言）。"
    echo "     退出码 77 = 不是通过。"
    exit 77
    ;;
esac

WPS="${LITEMASK_WINDOWS_INSTALL_PS1:-$ROOT/tools/e2e-windows-install.ps1}"
if [ ! -f "$WPS" ]; then
  echo "FAIL 找不到 tools/e2e-windows-install.ps1"
  exit 1
fi
echo "   跑 ${WPS}"
"$PWSH" -NoProfile -ExecutionPolicy Bypass -File "$WPS"
rc=$?
if [ $rc -eq 77 ]; then
  echo "SKIP Windows 侧脚本认为环境不具备（退出码 77）—— 不是通过"
fi
exit $rc
