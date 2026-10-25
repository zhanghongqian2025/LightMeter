#!/usr/bin/env bash
# OFD 跨对象续接的端到端：走**命令行 + 打包后的 jar**，不走测试夹具。
#
# 【这一条端到端守的是什么】
#   OFD 是**绝对定位**版式：每个 TextObject 自带 Boundary（毫米坐标）。
#   同一段文字被切成两个对象时，只有「同一部件、同一行、水平紧邻」才允许续接；
#   左右两栏、跨页、上下两行都必须**不续接**——
#   拼错了就是把不存在的字符串写进文档，属于版面事故，且不可逆。
#
# 校验由 tools/verify_ofd_continuation.py 独立完成（Python 重新实现了一遍
# 「OFD 里文本在哪、版面变没变、令牌归谁」），理由与其它 e2e 一样：
# 被测实现自己验自己，只能证明内部自洽。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
if [ "${LITEMASK_RUN_JAVA:-}" != "" ]; then
  echo "运行产品用的 java：${RUN_JAVA}（打包/样本生成仍用 ${JAVA_HOME}）"
fi
MVN="$MVN_BIN"

WORK="$ROOT/target/e2e-ofd-continuation"
JAR="$ROOT/target/desens-core.jar"

. "$ROOT/tools/e2e-common.sh"
e2e_form_banner

# 每次重打：避免拿一个陈旧的 jar 跑出「通过」。
"$MVN" -B -o -Dstyle.color=never -f "$ROOT/pom.xml" -DskipTests package > /dev/null

rm_rf "$WORK"
mkdir -p "$WORK/samples"

echo "=== 1. 生成三种切法的样本（纯 Python 造包，不 import 项目代码） ==="
python3 "$ROOT/tools/gen_ofd_geo_sample.py" "$WORK/samples"

MANIFEST="$WORK/samples/samples.json"

# 场景名与「该被脱成什么样」的关系写在清单里，脚本只负责执行。
CASES=$(python3 -c "
import json,sys
m=json.load(open(sys.argv[1],encoding='utf-8'))
print(' '.join(c['name'] for c in m['cases']))
" "$MANIFEST")

echo
echo "=== 2. 逐场景：命令行脱敏 → 独立校验 → 命令行还原 → 往返校验 ==="
TOTAL_ASSERT=0
for NAME in $CASES; do
  SRC="$WORK/samples/${NAME}.ofd"
  RED="$WORK/samples/${NAME}.redacted.ofd"
  MAP="$WORK/samples/${NAME}.ofd.redacted.map.json"
  RESTORED="$WORK/samples/${NAME}.redacted.restored.ofd"
  VOUT="$WORK/${NAME}.verify.out"

  echo "--- 场景「${NAME}」：脱敏 ---"
  prod -jar "$JAR" redact "$SRC" | tee "$WORK/${NAME}.redact.out"
  e2e_form_check "$WORK/${NAME}.redact.out"

  [ -f "$RED" ] || { echo "✗ 没有产出脱敏文件：$RED" >&2; exit 1; }
  [ -f "$MAP" ] || { echo "✗ 没有产出映射文件：$MAP" >&2; exit 1; }

  echo "--- 场景「${NAME}」：独立校验 ---"
  # 校验器的输出先落文件：既要能在失败时原样打出来，
  # 又要从中**数出断言条数**（那个数要进文档，不能手抄）。
  if ! python3 "$ROOT/tools/verify_ofd_continuation.py" case "$MANIFEST" "$NAME" "$SRC" "$RED" "$MAP" > "$VOUT" 2>&1; then
    cat "$VOUT" >&2
    echo "✗ 场景「${NAME}」的独立校验没过" >&2
    exit 1
  fi
  cat "$VOUT"
  n=$(grep -oE "校验通过（[0-9]+ 条断言）" "$VOUT" | grep -oE "[0-9]+" | head -1)
  TOTAL_ASSERT=$((TOTAL_ASSERT + ${n:-0}))

  echo "--- 场景「${NAME}」：命令行还原 ---"
  prod -jar "$JAR" restore "$RED" --map "$MAP"
  [ -f "$RESTORED" ] || { echo "✗ 没有产出还原文件：$RESTORED" >&2; exit 1; }

  echo "--- 场景「${NAME}」：往返校验 ---"
  if ! python3 "$ROOT/tools/verify_ofd_continuation.py" roundtrip "$MANIFEST" "$NAME" "$SRC" "$RESTORED" > "$VOUT" 2>&1; then
    cat "$VOUT" >&2
    echo "✗ 场景「${NAME}」的往返校验没过" >&2
    exit 1
  fi
  cat "$VOUT"
  n=$(grep -oE "校验通过（[0-9]+ 条断言）" "$VOUT" | grep -oE "[0-9]+" | head -1)
  TOTAL_ASSERT=$((TOTAL_ASSERT + ${n:-0}))
  echo
done

echo "=== 3. 体积 ==="
ls -l "$JAR" | awk '{printf "  fat jar: %.2f MB\n", $5/1048576}'

# 断言条数是**现算**的：文档里要写「端到端 N 条断言全过」，
# 那个数必须来自这一轮的实际输出，不能手抄（手抄的计数一定会漂）。
echo "端到端：pass=${TOTAL_ASSERT} fail=0"

echo "=== e2e-ofd-continuation 通过 ==="
