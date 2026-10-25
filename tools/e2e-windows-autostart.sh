#!/usr/bin/env bash
# Windows 开机自启（注册表 Run 键）：端到端。
#
# 【这一条为什么和别的 e2e 不一样】
#   别的 e2e 都在 macOS 上跑；这一条**必须在真 Windows 上跑**，
#   因为它验的是 `reg.exe` 的真实行为 —— 「我们让 reg add 写进去的那条命令行，
#   注册表里到底存成了什么」。这是本块**唯一**没法在 macOS 上验的东西。
#   → 在 macOS 上它做两件事：
#       1) **编译**驱动 —— 保证交给 Windows 的那份代码不会因为一个笔误跑不起来；
#          （用户在 Windows 那边第一次跑就编译失败，等于整条验证路径是死的，
#            而这个事实要等到用户手上有 Windows 机器才会暴露。）
#       2) 报告 SKIP，退出码 77 —— **不是通过**。
#   在 Windows（Git Bash / MSYS / Cygwin）上它会真的跑。
#
# 【一个踩过的坑：变量后面紧跟中文标点时必须写 ${VAR}】
#   bash 按**字节**解析变量名，而 `）` 是全角括号（多字节）——
#   在某些 locale 下 `$OS）` 会被解析成变量名 `OS<半个字符>` → 「unbound variable」。
#   → 凡是变量后面紧跟非 ASCII，一律写 `${VAR}`。
#
# 【判据独立于被测实现】
#   驱动自己调 reg.exe 读注册表、自己拼期望的命令行、自己写了解析 reg query 输出的那几行，
#   **一条都不复用产品代码**（WindowsRunKey / WindowsAutostart 都不 import）。
#
# 【退出码】
#   0  = 全部跑完且通过
#   1  = 有断言失败
#   77 = 环境不具备（不是 Windows）——**不是通过**
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
JAVAC="${LITEMASK_RUN_JAVAC:-$JAVA_HOME/bin/javac}"

. "$ROOT/tools/shell-lib.sh"

WORK="$(mktemp -d)"
cleanup() { rm_rf "$WORK"; }
trap cleanup EXIT

echo "== 0. 编译端到端驱动 =="
if ! "$JAVAC" -encoding UTF-8 -d "$WORK/classes" "$ROOT/tools/E2eWindowsAutostart.java"; then
  echo "FAIL 驱动编译不过 —— 交给 Windows 的那份代码是坏的"
  exit 1
fi
echo "OK 驱动编译通过"

OS="$(uname -s 2>/dev/null || echo unknown)"
case "$OS" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *)
    echo "SKIP 这不是 Windows（uname -s = ${OS}）。"
    echo "     Windows 开机自启的端到端必须在真 Windows 上跑；"
    echo "     本机只完成了「驱动能编译」这一项。退出码 77 = 不是通过。"
    exit 77
    ;;
esac

echo "== 1. 找产品可执行文件 =="
EXE="${LITEMASK_WINDOWS_EXE:-}"
if [ -z "$EXE" ]; then
  for cand in \
    "$ROOT/target/app-image/LightMeter.exe" \
    "$ROOT/target/app-image/LightMeter/LightMeter.exe" \
    "$ROOT/target/app-image/LightMeter.exe"; do
    if [ -f "$cand" ]; then EXE="$cand"; break; fi
  done
fi
if [ -z "$EXE" ] || [ ! -f "$EXE" ]; then
  echo "FAIL 找不到 LightMeter.exe。"
  echo "     先跑 tools/package-windows.ps1，或用 LITEMASK_WINDOWS_EXE 指定它的完整路径。"
  exit 1
fi
echo "   $EXE"

echo "== 2. 跑端到端 =="
PORT="${LITEMASK_WINDOWS_PORT:-8090}"
"$RUN_JAVA" -cp "$WORK/classes" E2eWindowsAutostart "$EXE" "$PORT"
rc=$?
if [ "$rc" -eq 77 ]; then
  echo "SKIP 驱动认为环境不具备（退出码 77）—— 不是通过"
fi
exit $rc
