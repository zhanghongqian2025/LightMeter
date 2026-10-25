#!/usr/bin/env bash
# OFD 端到端：走**命令行 + 打包后的 jar**，不走测试夹具。
#
# 校验由 tools/verify_ofd.py 独立完成（Python 重新实现了一遍「OFD 里文本在哪」），
# 理由和单测一样：被测实现自己验自己，只能证明内部自洽。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 打包用完整 JDK，跑产品用可替换的 java——理由见 tools/e2e-serve.sh 头部注释。
# 只有 `-jar "$JAR"` 那些是产品；样本生成器（javac + E2eOfdSample）留在完整 JDK。
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
if [ "${LITEMASK_RUN_JAVA:-}" != "" ]; then
  echo "运行产品用的 java：${RUN_JAVA}（打包/样本生成仍用 ${JAVA_HOME}）"
fi
MVN="$MVN_BIN"

WORK="$ROOT/target/e2e-ofd"
JAR="$ROOT/target/desens-core.jar"

# 运行形态由 e2e-common 决定（默认无模型 = pkg 交付形态；LITEMASK_E2E_NER=<目录> 则带模型）。
. "$ROOT/tools/e2e-common.sh"
e2e_form_banner

# 每次重打：沿用 PDF 端到端的做法，避免拿一个陈旧的 jar 跑出「通过」。
"$MVN" -B -o -Dstyle.color=never -f "$ROOT/pom.xml" -DskipTests package > /dev/null

rm_rf "$WORK"
mkdir -p "$WORK/classes"

echo "=== 1. 生成样本 ==="
javac -d "$WORK/classes" "$ROOT/tools/E2eOfdSample.java"
java -cp "$WORK/classes" E2eOfdSample "$WORK/卷宗.ofd"

PHONE=13800138000
PHONE_SPLIT=13900139000
ID=11010519491231002X
EMAIL=zhang@example.com
CARD=622202020202020207
USCC=91110108690017781B
AUTHOR=张洪千

# 拆开的那串手机号在文件里从来不是一段连续字节——它被切进了两个 TextCode，
# 所以既不能用它做「样本自检」，也不能靠字节扫描证明它没了。
# 能证明的是它那两半：脱敏前在，脱敏后不在。
SPLIT_A=${PHONE_SPLIT:0:7}
SPLIT_B=${PHONE_SPLIT:7}

VALUES=("$PHONE" "$ID" "$EMAIL" "$CARD" "$USCC" "$AUTHOR" "$SPLIT_A" "$SPLIT_B")

echo "=== 2. 样本自检（脱敏前这些值必须在里面，否则后面的「0 残留」是空转） ==="
python3 "$ROOT/tools/verify_ofd.py" require "$WORK/卷宗.ofd" "${VALUES[@]}"

echo "=== 3. 命令行脱敏 ==="
prod -jar "$JAR" redact "$WORK/卷宗.ofd" | tee "$WORK/redact.out"
e2e_form_check "$WORK/redact.out"

RED="${WORK}/卷宗.redacted.ofd"
MAP="${WORK}/卷宗.ofd.redacted.map.json"
[ -f "$RED" ] || { echo "✗ 没有产出脱敏文件" >&2; exit 1; }
[ -f "$MAP" ] || { echo "✗ 没有产出映射文件" >&2; exit 1; }

echo "=== 4. 字节级校验（Python 独立实现） ==="
python3 "$ROOT/tools/verify_ofd.py" scan "$RED" "${VALUES[@]}"
python3 "$ROOT/tools/verify_ofd.py" check-redacted "$RED" "手机号,身份证,邮箱,银行卡号,信用代码"

echo "=== 5. 命令行还原 ==="
prod -jar "$JAR" restore "$RED" --map "$MAP"
RESTORED="$WORK/卷宗.redacted.restored.ofd"
[ -f "$RESTORED" ] || { echo "✗ 没有产出还原文件" >&2; exit 1; }

echo "=== 6. 往返校验 ==="
python3 "$ROOT/tools/verify_ofd.py" check-roundtrip "$WORK/卷宗.ofd" "$RESTORED"

echo "=== 7. 体积 ==="
ls -l "$JAR" | awk '{printf "  fat jar: %.2f MB\n", $5/1048576}'

echo "=== e2e-ofd 通过 ==="
