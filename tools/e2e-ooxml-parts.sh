#!/usr/bin/env bash
# 块 15 专项端到端：OOXML 侧「跨 zip 部件不得续接」在**真实包**上成立。
#
# 【为什么单测之外还要这一份】
#   单测用的是**夹具**造的小包；单测绿只证明「夹具那种结构下不续接」。
#   这一份用**手写的真实包结构**（[Content_Types].xml / _rels / 各部件），
#   走**打包后的 jar + 命令行**脱敏，
#   校验由 tools/verify_ooxml_parts.py **独立**完成（只做字面扫描，不 import 项目代码）——
#   标尺必须独立于被测实现。
#
# 【这一份要证明什么】
#   块 13/14 的 OOXML 侧分组是「整篇一组」→ 跨 zip 部件的文本会被**拼在一起**：
#     页眉末尾一个 16 位数字 + 正文开头一个字母
#     → 17 位、含字母、字符集合法 = **形态合法的 VIN**（车架号）
#     → 两段都被按车架号脱敏：**误判 + 标签错**（原文里根本没有车架号）。
#   块 15 改成「一个 zip 部件 = 一组」后，跨部件的这两段必须**各自保持原样**；
#   而**同一部件内部**的切断仍必须续接（否则就把续接整体关掉了）。
#
# 【六条腿：3 种格式 × 2 种部件顺序】
#   为什么要两种部件顺序 —— 摊平成一组时续接是**相邻单元**拼接，
#   部件序决定「谁是邻居」。只造一种顺序的话，
#   另一种顺序下这条标尺会**空转**（本脚本第一版就踩了这个：pptx 变异体下全绿）。
#
# 【前提】
#   需要一个**已打包**的 jar。本脚本不自己打包（打包很慢，且会与并行任务抢 target/），
#   用 LITEMASK_JAR 环境变量指过来；默认取 target/desens-core.jar。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:${PATH}"
PY="${PY:-python3}"
JAR="${LITEMASK_JAR:-$ROOT/target/desens-core.jar}"

# jar 不在就自己打一个。
# 【为什么不无条件用现成的】
#   单独跑这个脚本时 target/ 里可能没有 jar（没打过包）→
#   那时它会「找不到 jar」直接退出，看起来像「没验」而不是「环境不具备」。
#   → 这里自己打一次；打包失败就报错退出（不静默跳过）。
if [ ! -f "$JAR" ]; then
  echo "== 没找到 jar，打包一个 =="
  MVN="${LITEMASK_MVN:-$MVN_BIN}"
  "$MVN" -B -o -Dstyle.color=never -f "$ROOT/pom.xml" -DskipTests package > /dev/null 2>&1
  rc=$?
  if [ $rc -ne 0 ] || [ ! -f "$JAR" ]; then
    echo "!! 打包失败（退出码 $rc），jar 仍不存在：$JAR"
    exit 1
  fi
  echo "    打包完成：$JAR"
fi

WORK="$(mktemp -d)"
trap 'python3 -c "import shutil,sys; shutil.rmtree(sys.argv[1], ignore_errors=True)" "$WORK"' EXIT

pass=0; fail=0
check() { # check <描述> <退出码>
  if [ "$2" -eq 0 ]; then echo "PASS $1"; pass=$((pass+1))
  else echo "FAIL $1"; fail=$((fail+1)); fi
}

echo "== 用 jar：$JAR =="
echo "== 造样本（手写 XML，zipfile + 纯字符串，不 import 项目代码）=="
"$PY" "$ROOT/tools/verify_ooxml_parts.py" --out "$WORK/src" || exit 1

for fmt in docx xlsx pptx; do
  for order in A B; do
    leg="$fmt-$order"
    SRC="$WORK/src/case-$leg.$fmt"
    DST="$WORK/out/case-$leg.$fmt"
    mkdir -p "$WORK/out"
    java -jar "$JAR" redact "$SRC" -o "$DST" --map "$WORK/out/map-$leg.json" > "$WORK/redact-$leg.log" 2>&1
    rc=$?
    if [ $rc -ne 0 ]; then
      echo "FAIL ${leg}：脱敏退出码 $rc"; sed 's/^/    /' "$WORK/redact-$leg.log"; fail=$((fail+1)); continue
    fi
    "$PY" "$ROOT/tools/verify_ooxml_parts.py" --check "$DST" --leg "$leg" > "$WORK/check-$leg.txt" 2>&1
    rc=$?
    if [ $rc -eq 0 ]; then
      echo "PASS ${leg}：跨部件不得续接（数字与字母各自原样、同部件内仍续接、无「车架号」标签）"
      pass=$((pass+1))
    else
      echo "FAIL ${leg}："; sed 's/^/    /' "$WORK/check-$leg.txt"; fail=$((fail+1))
    fi
  done
done

echo
echo "=== e2e-ooxml-parts: pass=$pass fail=$fail ==="
[ "$fail" -eq 0 ] || exit 1
exit 0
