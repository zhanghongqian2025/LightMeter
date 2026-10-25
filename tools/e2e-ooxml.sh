#!/usr/bin/env bash
# OOXML 三条管线（docx / xlsx / pptx）端到端：走打包后的 jar + 命令行。
#
# 为什么要补这个脚本（M3-4 踩出来的）：
#   交付物是 jlink 定制运行时，而「模块集够不够用」只能靠
#   **每一条管线都在那个运行时上真跑一遍**来证明。
#   原来只有 pdf / ofd / serve / untrusted 四个 e2e，docx / xlsx / pptx
#   从来只在单测里跑过——而单测跑在完整 JDK 上。
#   结果就是：java.desktop 被误判成「可以砍」（见 docs/M3-4-阶段状态.md），
#   一直没人发现，因为**没有任何一条检查会在裁过的运行时上跑 OOXML**。
#
# 校验由 tools/verify_ooxml.py 独立完成（Python + zipfile 重新实现了一遍
# 「包里文本在哪」），样本由 tools/gen_ooxml_sample.py 用手写 XML 现造，
# 两者都不 import 任何项目代码——理由同其它 e2e：标尺必须独立于被测实现。
#
# 用法：
#   ./tools/e2e-ooxml.sh                                  # 用完整 JDK 跑
#   LITEMASK_RUN_JAVA=/path/to/jlink-rt/bin/java \
#       ./tools/e2e-ooxml.sh                             # 用交付运行时跑
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
if [ "${LITEMASK_RUN_JAVA:-}" != "" ]; then
  echo "运行产品用的 java：${RUN_JAVA}（打包仍用 ${JAVA_HOME}）"
fi
MVN="$MVN_BIN -B -o -Dstyle.color=never"

JAR="$ROOT/target/desens-core.jar"
PY="${PY:-python3}"

# 运行形态由 e2e-common 决定（默认无模型 = pkg 交付形态；LITEMASK_E2E_NER=<目录> 则带模型）。
. "$ROOT/tools/e2e-common.sh"
e2e_form_banner

WORK="$(mktemp -d)"
trap 'rm_rf "$WORK"' EXIT
pass=0; fail=0

check() { # check <描述> <条件表达式退出码>
  if [ "$2" -eq 0 ]; then echo "PASS $1"; pass=$((pass+1))
  else echo "FAIL $1"; fail=$((fail+1)); fi
}

echo "== 构建 fat jar =="
$MVN -q -f "$ROOT/pom.xml" -DskipTests package > /dev/null || { echo "打包失败"; exit 1; }
echo "    $(wc -c < "$JAR" | tr -d ' ') 字节"

echo "== 造样本（手写 XML，敏感值放在容易漏的位置）=="
"$PY" "$ROOT/tools/gen_ooxml_sample.py" --out "$WORK/src" > /dev/null || exit 1
ls -1 "$WORK/src"

for fmt in docx xlsx pptx; do
  SRC="$WORK/src/case.$fmt"
  cp "$SRC" "$WORK/$fmt-in.$fmt"

  echo ""
  echo "===== $fmt ====="
  if ! prod -jar "$JAR" redact "$WORK/$fmt-in.$fmt" 2>"$WORK/$fmt.err" \
       | tee "$WORK/$fmt.out"; then
    echo "FAIL $fmt 脱敏命令非零退出"
    head -20 "$WORK/$fmt.err"
    fail=$((fail+1))
    continue
  fi
  check "$fmt 脱敏命令退出码 0" 0
  # 形态自证：只看文档内容对不对是不够的——形态不对时，内容「对」也是另一个形态的对。
  # 走 e2e_form_check（失败即退出）而不是 fail++：形态错了，后面每一条断言
  # 都在测一个不存在的东西，继续数只会把「测错了形态」包装成「几条小失败」。
  e2e_form_check "$WORK/$fmt.out"

  # 命名口径：脱敏件是 <基名>.redacted.<后缀>，映射是 <原文件名>.redacted.map.json。
  # 两者不是同一个派生规则（一个有扩展名一个没有），是产品现状；这里照抄，
  # 免得端到端因为猜错路径而报「没产出」。
  RED="$WORK/$fmt-in.redacted.$fmt"
  MAP="$WORK/$fmt-in.$fmt.redacted.map.json"
  [ -f "$RED" ]  && echo "PASS $fmt 产出脱敏件" && pass=$((pass+1)) \
                  || { echo "FAIL $fmt 没产出脱敏件"; fail=$((fail+1)); continue; }
  [ -f "$MAP" ] && echo "PASS $fmt 产出映射" && pass=$((pass+1)) \
                 || { echo "FAIL $fmt 没产出映射"; fail=$((fail+1)); continue; }

  echo "-- 全包扫描：原值与**碎片**都不得出现在任何部件、任何条目名里 --"
  # 碎片也在扫描范围内：标识符被段落/单元格切断后，包里剩下的是碎片而不是完整值，
  # 只查完整值的话「碎片原样留在交付物里」是查不出来的（这正是 M3-4 记错的地方）。
  if "$PY" "$ROOT/tools/verify_ooxml.py" check --pkg "$RED" --map "$MAP" \
        --values "$WORK/src/values.json"; then
    echo "PASS $fmt 全包无原值/碎片残留 + 映射齐全 + 令牌已写入"; pass=$((pass+1))
  else
    echo "FAIL $fmt 校验器报了问题"; fail=$((fail+1))
  fi

  echo "-- 还原往返 --"
  # 还原产物是 <基名>.restored.<后缀>，即 a.redacted.docx -> a.redacted.restored.docx
  RESTORED="$WORK/$fmt-in.redacted.restored.$fmt"
  if prod -jar "$JAR" restore "$RED" --map "$MAP" >/dev/null 2>&1 \
     && [ -f "$RESTORED" ]; then
    if "$PY" "$ROOT/tools/verify_ooxml.py" roundtrip --orig "$SRC" \
          --redacted "$RED" --restored "$RESTORED"; then
      echo "PASS $fmt 还原后与原件逐字一致"; pass=$((pass+1))
    else
      echo "FAIL $fmt 还原后与原件不一致"; fail=$((fail+1))
    fi
  else
    echo "FAIL $fmt 还原命令非零退出或没产出（找的是 ${RESTORED}）"; fail=$((fail+1))
  fi
done

echo ""
echo "=== e2e-ooxml: pass=$pass fail=$fail ==="
[ "$fail" -eq 0 ] || exit 1
