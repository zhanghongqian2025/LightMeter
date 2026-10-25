#!/usr/bin/env bash
# PDF 管线端到端验证：用**命令行**（不是内部 API）跑一份满是坑的真实中文样本，
# 再用一份**完全独立的校验程序**检查「原文没了」和「还原得了」。
#
# 校验程序只用 PDFBox 自己的文本引擎和原始字节扫描，不碰被测代码的解码逻辑——
# 否则被测实现解码写错时，校验会跟着错，测出来的东西是假的。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 打包用完整 JDK，跑产品用可替换的 java——理由见 tools/e2e-serve.sh 头部注释。
# 只有 `-jar "$JAR"` 那些是产品，换成精简运行时；
# 样本生成器与校验器仍用完整 JDK：它们是测试夹具与外部观察者，
# 让它们跟着裁模块会把「夹具需要某个模块」误判成「产品缺模块」。
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
if [ "${LITEMASK_RUN_JAVA:-}" != "" ]; then
  echo "运行产品用的 java：${RUN_JAVA}（打包/样本/校验仍用 ${JAVA_HOME}）"
fi
MVN="$MVN_BIN -B -o -Dstyle.color=never"
JAR="$ROOT/target/desens-core.jar"

# 运行形态由 e2e-common 决定（默认无模型 = pkg 交付形态；LITEMASK_E2E_NER=<目录> 则带模型）。
# 必须在 RUN_JAVA 定义之后 source：common 里的 prod() 要用它。
. "$ROOT/tools/e2e-common.sh"
e2e_form_banner

# 字体用**仓库自带的子集**（Noto Sans SC 裁剪版，SIL OFL 1.1，可再分发），
# 不再挑系统字体。原来的系统字体列表有三个问题，都是实测出来的：
#   1) Windows 上唯一的候选 C:/Windows/Fonts/msyh.ttc 是**字体集**，
#      而 PDFBox 的 PDType0Font.load 读不了它（抛 "'head' table is mandatory"）；
#   2) 「本机没字体就退出」让这条端到端在很多机器上**从来没执行过**；
#   3) 不同机器的字体度量不同，版面断言的数值随机器变。
FONT="$ROOT/src/test/resources/fonts/litemask-cjk-test.ttf"
if [ ! -r "$FONT" ]; then
  echo "仓库自带的中文字体子集缺失：$FONT"
  echo "用 python3 tools/make_test_font.py --source <NotoSansSC-VF.ttf> 生成。"
  echo "端到端 PDF 验证**未执行**。这是明确的验证缺口，不是已通过。"
  exit 1
fi

# 每次都重打：只在 jar 缺失时才打的话，改完源码跑出来的是**旧 jar** 的结果，
# 而端到端恰恰是最容易骗过自己的地方。
$MVN -q -f "$ROOT/pom.xml" -DskipTests package || { echo "打包失败"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm_rf "$WORK"' EXIT
SAMPLE="$WORK/卷宗.pdf"

echo "=== 生成样本（含中文字体 / 跨算子切分 / 不可见 OCR 层 / 元数据 / 批注 / 附件 / JS / 书签）==="
javac -encoding UTF-8 -cp "$JAR" -d "$WORK/gen" "$ROOT/tools/E2ePdfSample.java" || exit 1
java -cp "$JAR:$WORK/gen" E2ePdfSample gen "$SAMPLE" "$FONT" || exit 1

echo
echo "=== 守卫：字体集（.ttc）必须被明确拒绝，而不是甩一句底层异常 ==="
# 这条不是走过场。原来的样本生成器拿系统字体（Windows 上只有 msyh.ttc），
# 一旦挑到字体集就是一句 "'head' table is mandatory"，读日志的人无从下手。
# 这里把「拒绝得明确」变成端到端的一部分：工具自己给出的提示必须可操作。
TTC="$ROOT/src/test/resources/fonts/litemask-cjk-test.ttc"
if [ ! -r "$TTC" ]; then
  echo "X 守卫用的字体集缺失：${TTC}（应由 tools/make_test_font.py 一并生成）"
  exit 1
fi
if java -cp "$JAR:$WORK/gen" E2ePdfSample gen "$WORK/ttc-should-not-exist.pdf" "$TTC" >"$WORK/ttc.out" 2>&1; then
  echo "X 字体集竟然被接受了——守卫失效，样本生成器会写出一份坏 PDF"
  exit 1
fi
if ! grep -q "TrueTypeCollection" "$WORK/ttc.out"; then
  echo "X 报错没有给出可操作提示，实际输出："
  cat "$WORK/ttc.out"
  exit 1
fi
echo "OK 字体集已被明确拒绝，并给出了正确做法"

echo
echo "=== redact ==="
# 走 prod 而不是裸的 "$RUN_JAVA"：形态（无模型 / 带模型）由 tools/e2e-common.sh 决定，
# 隔离必须只作用在产品调用上——夹具和 $MVN 不能跟着改 user.home。
prod -jar "$JAR" redact "$SAMPLE" | tee "$WORK/redact.out" || { echo "脱敏失败"; exit 1; }
echo
e2e_form_check "$WORK/redact.out"

MAP="$SAMPLE.redacted.map.json"
[ -f "$MAP" ] || { echo "映射文件未生成: $MAP"; exit 1; }

echo
echo "=== 映射件里必须带位置（页 + 文本块）==="
# 位置信息是这一轮才有的：以前 PDF 的命中是摊平的，只知道「哪条规则、哪个原值」，
# 不知道「在哪儿」。用户拿映射去核对源文件时，找不到那一处在哪一页。
# 校验器**不碰**被测代码的解码逻辑：这里只读映射 JSON 自己的字段。
python3 -c "
import json, sys
m = json.load(open(sys.argv[1], encoding='utf-8'))
ch = m.get('changes') or []
assert ch, '映射件里没有 changes'
bad = [c for c in ch if not c.get('part') or not c.get('locator')]
assert not bad, '有 %d 条命中缺位置，例如 %r' % (len(bad), bad[:3])
assert any(str(c['part']).startswith('第 ') for c in ch), '位置里没有页码：%r' % (ch[:2],)
print('OK %d 条命中都带位置，例如「%s %s」' % (len(ch), ch[0]['part'], ch[0]['locator']))
" "$MAP" || { echo "映射件里没有位置信息"; exit 1; }

echo
echo "=== restore ==="
# 注意用 ${SAMPLE%.pdf} 而不是 $SAMPLE.redacted.pdf：
# bash 的变量名只吃 [A-Za-z0-9_]，后者会把 ".redacted.pdf" 当成字面量接到 "$SAMPLE" 后面，
# 变成 卷宗.pdf.redacted.pdf —— 一个不存在的路径，报错信息还只是一句路径，排查起来很费劲。
REDACTED="${SAMPLE%.pdf}.redacted.pdf"
prod -jar "$JAR" restore "$REDACTED" --map "$MAP" | tee "$WORK/restore.out" || { echo "还原失败"; exit 1; }
RESTORED="${REDACTED%.pdf}.restored.pdf"

# 还原不得有版面告警。
# 「版面自检未能完成」说的是「**没查**」，不是「查过没问题」——
# 自检一崩，「版面是否保持原样」就再没人核对了。
# 2026-10-20 实测：还原时写回的 Tz 与前一个算子粘成了「Tz100」，
# 内容流畸形，我们自己的扫描器读不懂 → 自检直接崩 → 原文被按压缩后的宽度画回去。
# 这条判据就是为它立的。
if grep -q "版面" "$WORK/restore.out"; then
  echo "FAIL 还原出现版面告警："
  grep "版面" "$WORK/restore.out"
  exit 1
fi
echo "OK 还原无版面告警（版面自检跑完且无差异）"

echo
echo "=== 独立校验 ==="
java -cp "$JAR:$WORK/gen" E2ePdfSample check "$SAMPLE" "$REDACTED" "$RESTORED" "$WORK/redact.out"
code=$?

echo
echo "=== 碎片级：身份证被文本块切断（块 13 实测的「整张留在交付物里」）==="
# 这段单独立项，因为它测的是**另一种失败**：上面那份样本里的值都是完整的，
# 而真实卷宗里身份证经常被排版切成两段——那时规则匹配不上任何东西，
# 碎片就原样留在交付物里，而工具照样报告「脱敏成功」。
# 块 13 实测：切法 14+3 时 PDF 侧是 0 处命中、整张身份证原样留着（泄漏）。
FRAG="$WORK/碎片.pdf"
javac -encoding UTF-8 -cp "$JAR" -d "$WORK/gen" "$ROOT/tools/E2ePdfFragment.java" || exit 1
java -cp "$JAR:$WORK/gen" E2ePdfFragment gen "$FRAG" "$FONT" || exit 1

prod -jar "$JAR" redact "$FRAG" | tee "$WORK/frag.out" || { echo "碎片样本脱敏失败"; exit 1; }
echo
e2e_form_check "$WORK/frag.out"

FRAG_RED="${FRAG%.pdf}.redacted.pdf"
FRAG_MAP="$FRAG.redacted.map.json"
[ -f "$FRAG_RED" ] || { echo "碎片样本没产出脱敏件"; exit 1; }
[ -f "$FRAG_MAP" ] || { echo "碎片样本没产出映射"; exit 1; }

echo
echo "-- 映射里必须有两段碎片，且令牌类型是身份证 --"
# 只查文本层不够：碎片「被当成一个值」这件事的凭据在映射里。
# 令牌类型也要查——块 13 之前 6+12 的切法被错标成手机号（<PHONE_1>），
# 那是标签错、不泄漏，但用户按身份证去找就找不到。
python3 -c "
import json, sys
m = json.load(open(sys.argv[1], encoding='utf-8'))
mp = m.get('mapping') or {}
frags = ['11010519491231', '002X', '110105', '19491231002X', '11010519491231002X']
missing = [f for f in frags if f not in mp.values()]
assert not missing, '映射里缺少这些碎片：%r（实际映射：%r）' % (missing, mp)
bad = [(k, v) for k, v in mp.items() if v in frags and not k.startswith('<ID_')]
assert not bad, '碎片的令牌类型不是身份证：%r' % (bad,)
hit = sum(1 for v in mp.values() if v in frags)
print('OK 映射里有 %d 条碎片记录，令牌类型均为 <ID_n>' % hit)
" "$FRAG_MAP" || { echo "映射件里没有碎片，或类型不对"; exit 1; }

echo
echo "-- 独立校验：碎片残留 / 令牌类型 / 还原逐字一致 --"
prod -jar "$JAR" restore "$FRAG_RED" --map "$FRAG_MAP" | tee "$WORK/frag-restore.out" || { echo "碎片样本还原失败"; exit 1; }
FRAG_RESTORED="${FRAG_RED%.pdf}.restored.pdf"
if grep -q "版面" "$WORK/frag-restore.out"; then
  echo "FAIL 碎片样本还原出现版面告警："
  grep "版面" "$WORK/frag-restore.out"
  exit 1
fi
echo "OK 碎片样本还原无版面告警"
java -cp "$JAR:$WORK/gen" E2ePdfFragment check "$FRAG" "$FRAG_RED" "$FRAG_RESTORED"
fragcode=$?

echo
if [ $code -eq 0 ] && [ $fragcode -eq 0 ]; then
  echo "端到端：通过"
  exit 0
fi
echo "端到端：未通过（主体校验=$code 碎片级校验=$fragcode）"
exit 1
