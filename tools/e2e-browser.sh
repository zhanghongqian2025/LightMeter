#!/usr/bin/env bash
# M2-c 端到端（浏览器那半边）：用真实浏览器（Chrome 引擎）跑一遍界面流程。
#
# 【关于「真实浏览器」这两个字的可核查性】
#   本脚本按序探测可用的 Chrome 构建（见下面的 chrome_candidates），
#   并把**实际用的那个二进制的路径与版本**打进日志 ——
#   「真实浏览器跑通完整流程」这句话，只有在能指出是哪个构建时才可核查。
#   本机现状（2026-10-21）：/Applications 下的正式版 Chrome 已被移走，
#   实际用的是 Playwright 分发的 **Chrome for Testing headless shell**。
#   它是真的 Chrome 引擎（不是模拟器），但**没有 UI、没有扩展、只能无头跑** →
#   「图形界面观感」那一层**仍未验**（见 §19 缺口）。
#
# 【为什么要有这个脚本】——本项目长期缺口：界面那半边只有字符串匹配的静态断言。
# 那些断言看不见「脚本在浏览器里根本没跑起来」：
# app.js 只要有一个语法错误，页面就一片空白，而服务端几十条单测全绿。
#
# 【这个脚本验的是什么】
#   1. 真实浏览器里，首页能起来（规则表/姿态表/接口清单都被填上了）
#      —— 这一条等价于「app.js 真的执行了，没有语法错误、没有未捕获异常」。
#   2. 选文件 → 扫描 → 命中清单 → 应用 → 下载卡，界面状态一路推进。
#   3. 进度文案报的是「已处理 N 个文本单元」而不是百分比。
#   4. **真在浏览器里点一次取消**：界面进入「正在取消」，终态是「已取消」。
#      这一条是静态断言替不了的——它验的是事件真的接上了，而不只是「引用过这个 id」。
#
# 【负控制：怎么证明这个测试会咬人】
#   驱动支持在传输途中改写 app.js 再喂给页面：
#     - 删掉取消按钮的监听器（静态断言曾经咬不住的那一条）
#     - 制造一个语法错误
#   断言逻辑与正常跑完全一致，所以「变异后必须失败」是自证的。
#   这一段是本脚本的重点：没有它，一个永远绿的浏览器测试比没有测试更坏。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="${JAVA_HOME}/bin:${PATH}"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
MVN="$MVN_BIN -B -o -Dstyle.color=never"

PORT="${E2E_BROWSER_PORT:-18932}"
WORK="$(mktemp -d)"
JAR="$ROOT/target/desens-core.jar"
SRV_PID=""
pass=0; fail=0

ok()   { echo "PASS $1"; pass=$((pass+1)); }
bad()  { echo "FAIL $1"; fail=$((fail+1)); }
check(){ # $1=名称 $2=期望 $3=实际
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 $2，实际 $3）"; fi
}
check_ge(){ # $1=名称 $2=下限 $3=实际
  if [ "$3" -ge "$2" ] 2>/dev/null; then ok "$1"; else bad "$1（期望 ≥$2，实际 $3）"; fi
}

# 运行形态与其它 e2e 一致（默认无模型 = pkg 交付形态）。
. "$ROOT/tools/e2e-common.sh"
e2e_form_banner

# ---- 浏览器二进制：按序探测，并把**实际用的那个**打进日志 ----
#
# 【为什么不能只认 /Applications 下的正式版】
#   2026-10-21 23:14 这个路径还在，23:39 没了（不是本项目动的）→
#   e2e-browser 走 SKIP 分支 → 链判据「结论读不出来」把整条链判红。
#   一个**环境问题**被记成了**代码问题**，这正是最坏的那种红灯。
#   → 按序探测多个可用的 Chrome 构建；拿到哪个就用哪个。
#
# 【为什么要把路径与版本打进日志】
#   「真实浏览器跑通完整流程」这句话，只有在**能指出是哪个二进制**时才可核查。
#   完整版 Chrome 与 Chrome for Testing 的 headless shell 不是一回事
#   （headless shell 没有 UI、没有扩展、只能无头跑），
#   用它跑出来的结论必须让人一眼看出用的是哪个构建 —— 所以路径与版本要出现在日志里。
#
# 【E2E_CHROME 显式指定时不做回退】
#   显式指定 = 明确要求用这个；它不在就明说（SKIP），
#   不偷偷换一个浏览器来冒充「验过了」。
chrome_candidates() {
  if [ -n "${E2E_CHROME:-}" ]; then
    echo "$E2E_CHROME"
    return
  fi
  echo "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  echo "/Applications/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  ls -d "$HOME"/Library/Caches/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-mac-arm64/chrome-headless-shell 2>/dev/null
  ls -d "$HOME"/Library/Caches/ms-playwright/chromium-*/chrome-mac*/Chromium.app/Contents/MacOS/Chromium 2>/dev/null
}
CHROME="$(chrome_candidates | while IFS= read -r c; do
  [ -n "$c" ] || continue
  if [ -x "$c" ]; then echo "$c"; break; fi
done)"
BIG_PARAGRAPHS="${E2E_BIG_PARAGRAPHS:-8000}"
BIG_CHARS="${E2E_BIG_CHARS:-300}"

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  wait "$SRV_PID" 2>/dev/null
  [ -n "${WORK:-}" ] && rm_rf "${WORK}"
}
trap cleanup EXIT

if [ ! -x "$CHROME" ]; then
  echo "SKIP 没有可用的 Chrome（试过：$(chrome_candidates | tr '\n' ' ')）——浏览器那半边这一轮没验"
  exit 0
fi
if ! command -v node >/dev/null 2>&1; then
  echo "SKIP 没有 node——浏览器那半边这一轮没验"
  exit 0
fi

# 【实际用的浏览器是哪一个，要能被看见】
#   「真实浏览器跑通完整流程」这句结论的可核查性全靠这一段：
#   路径 + 版本写在日志里，读者才知道这份证据是**哪个构建**跑出来的。
echo "== 浏览器二进制 =="
echo "   路径：$CHROME"
echo "   版本：$("$CHROME" --version 2>&1 | head -1)"

echo "== 构建 fat jar =="
# 【为什么要显式清 target/classes】
#   对照实验与自证脚本会变异 app.js/app.css 再还原，还原保留 mtime，
#   target/classes 里可能留着上一轮的**变异体**；Maven 按时间戳判定
#   「资源无需重拷贝」就会把变异体打进 jar。实测踩过一次：
#   端到端三条断言全红（折叠没生效 / 排除项没进报文 / 提示语不更新），
#   查源码却是对的 —— 红在构建产物上。
#   → 构建前先把这棵目录删掉，资源一定重新拷贝。
#
# 【为什么不用 mvn clean】本机 Maven 离线运行，本地仓库里没有
#   maven-clean-plugin → `mvn clean` 报 PluginResolutionException，构建根本起不来。
#   → 用与对照实验同一套 python 删除（rm_rf 在 tools/shell-lib.sh 里）。
rm_rf "$ROOT/target/classes"
$MVN -f "$ROOT/pom.xml" package -DskipTests -q 2>&1 | tail -3
[ -f "$JAR" ] || { echo "X 没有产出 ${JAR}，实验无效"; exit 1; }
echo "   $(wc -c < "$JAR") 字节"

# 【前提自证】jar 里的界面资源必须与源码**逐字相符**。
#
# 为什么这道守卫是必需的（2026-10-21 实测踩到的坑）：
#   变异脚本还原源码时用 copy2/mv，把 mtime 还原到变异之前 →
#   源文件比 target/classes 里的旧拷贝更旧 → Maven 增量跳过重拷贝 →
#   jar 里装的是变异体。实测后果：端到端三条断言全红
#   （折叠没生效 / 排除项没进报文 / 提示语不更新），
#   看着像代码缺陷，根因却在构建产物——查源码是查不出毛病的。
#   守卫把前提显式断言出来：jar 与源码不一致，这一轮的结果一律不作数。
if ! python3 "$ROOT/tools/check_jar_web_matches_source.py" \
    "$JAR" "$ROOT/src/main/resources/web"; then
  echo "X jar 里的界面资源与源码不一致 —— 这一轮不作数（是构建产物脏了，不是代码有问题）"
  exit 1
fi

echo "== 造样本（不依赖被测代码）=="
python3 "$ROOT/tools/gen_browser_fixture.py" --out "${WORK}/fx" \
  --big-paragraphs "$BIG_PARAGRAPHS" --big-chars "$BIG_CHARS" | sed 's/^/   /'
# 长文件名的样本（名字是 60 个汉字），用来测窄屏下的换行与溢出。
LONG_FILE=$(ls "${WORK}/fx/"*长文件名*.docx 2>/dev/null | head -1)
if [ -z "$LONG_FILE" ]; then
  echo "X 没找到长文件名的样本，窄屏那几条没法测"
  exit 1
fi

# PDF 样本：PDF 的位置（第 N 页 · 文本块 M）必须在**界面上**显示出来。
# 用仓库自带的中文字体子集，与 e2e-pdf.sh 同一份：机器度量不同会让版面断言的数值随机器变。
PDF_FONT="$ROOT/src/test/resources/fonts/litemask-cjk-test.ttf"
PDF_SAMPLE="${WORK}/fx/sample.pdf"
if [ ! -r "$PDF_FONT" ]; then
  echo "X 仓库自带的中文字体子集缺失：$PDF_FONT"
  echo "X 「PDF 的位置在界面上显示出来」这一项**未执行**。这是明确的验证缺口，不是已通过。"
  exit 1
fi
javac -encoding UTF-8 -cp "$JAR" -d "${WORK}/gen" "$ROOT/tools/E2ePdfSample.java" || exit 1
java -cp "$JAR:${WORK}/gen" E2ePdfSample gen "$PDF_SAMPLE" "$PDF_FONT" || exit 1
echo "   PDF 样本：$(wc -c < "$PDF_SAMPLE") 字节"

echo "== 启动本地服务（端口 ${PORT}）=="
"${PROD_CMD[@]}" -jar "$JAR" serve \
  --port "$PORT" --workspace "${WORK}/ws" --no-browser --print-token \
  > "${WORK}/server.log" 2>&1 &
SRV_PID=$!

READY=""
for _ in $(seq 1 60); do
  if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
  if curl -s -o /dev/null "http://127.0.0.1:${PORT}/api/health" 2>/dev/null; then
    READY=yes; break
  fi
  sleep 0.25
done
if [ -z "$READY" ]; then
  echo "X 服务没起来，日志如下："; cat "${WORK}/server.log"; exit 1
fi

run_driver() { # $1=标签 $2=变异模式 $3=输出文件
  node "$ROOT/tools/browser-cdp.js" \
    --chrome "$CHROME" \
    --url "http://127.0.0.1:${PORT}/" \
    --file "${WORK}/fx/sample.docx" \
    --big-file "${WORK}/fx/big.docx" \
    --long-file "${LONG_FILE:-}" \
    --bad-file "${WORK}/fx/sample.exe" \
    --mutate "$2" \
    --artifact-dir "${WORK}/artifacts/$3" \
    --screenshot "${WORK}/$3.png" \
    > "${WORK}/$3.json" 2> "${WORK}/$3.log"
  return $?
}

jget() { # $1=json 文件 $2=表达式（表达式里用 d 表示根对象）
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2], {"d": d}))' "$1" "$2"
}

# PDF 专用驱动：**独立进程**跑 PDF 那一段。
# 为什么不给它套主流程：主流程那几十条断言走的是 docx，
# 往里插「再扫一次别的格式」会把一个稳定的流程搅浑，
# 出了错分不清是 PDF 的问题还是 docx 流程被搅坏了。
run_pdf_driver() { # $1=输出文件
  node "$ROOT/tools/browser-cdp.js" \
    --chrome "$CHROME" \
    --url "http://127.0.0.1:${PORT}/" \
    --pdf-only --pdf-file "$PDF_SAMPLE" \
    --screenshot "${WORK}/$1.png" \
    > "${WORK}/$1.json" 2> "${WORK}/$1.log"
  return $?
}

# ---------------------------------------------------------------------------
echo "== 1. 真实浏览器跑通完整流程 =="
run_driver real none real
rc=$?
check "驱动退出码" 0 "${rc}"
if [ "${rc}" -ne 0 ]; then
  echo "---- 驱动的失败明细 ----"
  cat "${WORK}/real.log"
  echo "----"
fi
TOTAL=$(jget "${WORK}/real.json" "d['total']")
FAILED=$(jget "${WORK}/real.json" "d['failed']")
# 下限而不是等值：等值会在「我改了断言清单」时静默变红，而这里的意图是
# 「驱动是不是把整条流程都跑完了」——少跑一段就该红。
check_ge "断言条数（整条流程都跑完了）" 30 "${TOTAL}"
check "失败条数" 0 "${FAILED}"
EXC=$(jget "${WORK}/real.json" "len(d['exceptions'])")
check "未捕获异常数" 0 "${EXC}"
CONS=$(jget "${WORK}/real.json" "len(d['consoleErrors'])")
check "控制台 error 数" 0 "${CONS}"
MUTNOTE=$(jget "${WORK}/real.json" "d['mutation']['note']")
check "未变异" "未变异" "${MUTNOTE}"
if [ -s "${WORK}/real.png" ]; then ok "截图已生成"; else bad "截图没生成"; fi
# 跳过的项（环境能力缺失）不当失败算，但要显眼地报出来：
# 混进「失败」里读者会以为产品有毛病，混进「通过」里读者会以为验过了。
SKIPN=$(jget "${WORK}/real.json" "len(d.get('skipped',[]))")
if [ "${SKIPN}" -gt 0 ]; then
  echo "WARN 有 ${SKIPN} 项没验到（环境能力缺失，不是产品缺陷）："
  jget "${WORK}/real.json" "';'.join(d.get('skipped',[]))" | sed 's/^/   /'
else
  ok "没有跳过的项"
fi

# ---------------------------------------------------------------------------
echo "== 2. 负控制①：删掉取消按钮的监听器，浏览器测试必须失败 =="
run_driver mut1 drop-cancel-listener mut1
rc=$?
check "变异后驱动退出码非 0" "1" "${rc}"
M1=$(jget "${WORK}/mut1.json" "d['mutation']['changed']")
check "变异确实改写了脚本" "True" "${M1}"
# 失败点必须是取消那一条：变异别的部分而这条还过，说明测试没有咬住真正的缺陷。
M1FAIL=$(jget "${WORK}/mut1.json" "';'.join(c['name'] for c in d['checks'] if not c['ok'])")
case "$M1FAIL" in
  *取消*) ok "失败点落在取消那一条（${M1FAIL}）" ;;
  *) bad "失败点没落在取消那一条（实际失败：${M1FAIL}）" ;;
esac
# 变异的是取消监听器，其它断言不该跟着一起塌（否则这条负控制说明不了问题）。
M1OTHER=$(jget "${WORK}/mut1.json" "len([c for c in d['checks'] if not c['ok'] and '取消' not in c['name']])")
check "其余断言不受影响" 0 "${M1OTHER}"

# ---------------------------------------------------------------------------
echo "== 3. 负控制②：脚本有语法错误，浏览器测试必须失败 =="
run_driver mut2 syntax-error mut2
rc=$?
check "变异后驱动退出码非 0" "1" "${rc}"
M2=$(jget "${WORK}/mut2.json" "d['mutation']['changed']")
check "变异确实改写了脚本" "True" "${M2}"
M2FAIL=$(jget "${WORK}/mut2.json" "';'.join(c['name'] for c in d['checks'] if not c['ok'])")
case "$M2FAIL" in
  *"规则表已填充"*) ok "失败点落在「规则表已填充」（脚本没跑起来）（${M2FAIL}）" ;;
  *) bad "失败点没落在「规则表已填充」（实际失败：${M2FAIL}）" ;;
esac
M2EXC=$(jget "${WORK}/mut2.json" "len(d['exceptions'])")
M2CONS=$(jget "${WORK}/mut2.json" "len(d['consoleErrors'])")
# 语法错误在经典脚本里是走 Log/console 报出来的，不一定走 Runtime.exceptionThrown，
# 所以两个通道都要看：只看一个会误判成「浏览器没报错」。
M2TOT=$((M2EXC + M2CONS))
if [ "${M2TOT}" -gt 0 ]; then
  ok "浏览器报出了错误（异常 ${M2EXC} 条 / 控制台 ${M2CONS} 条）"
else
  bad "浏览器一条错都没报 —— 语法错误应该被报出来"
fi

# ---------------------------------------------------------------------------
echo "== 5. PDF 的位置在界面上真的显示出来 =="
# 这一条盯的是**屏幕上的文字**。PDF 以前在界面上是「无位置信息」，
# 而位置信息其实一直在下发、只是从不显示——对用户等于没有。
# 这一轮补上之后，页码与文本块必须出现在清单的「出现处」那一列里。
run_pdf_driver pdf
rc=$?
check "PDF 位置驱动退出码" 0 "${rc}"
if [ $rc -ne 0 ]; then
  echo "---- 驱动的失败明细 ----"
  cat "${WORK}/pdf.log"
  echo "----"
fi
if [ -f "${WORK}/pdf.json" ]; then
  PDFTOT=$(jget "${WORK}/pdf.json" "d['total']")
  PDFFAIL=$(jget "${WORK}/pdf.json" "d['failed']")
  # 下限而不是等值：等值会在「我改了断言清单」时静默变红，
  # 而这里的意图是「PDF 那段流程都跑完了」——少跑一段就该红。
  check_ge "PDF 位置断言条数（那段流程都跑完了）" 5 "${PDFTOT}"
  check "PDF 位置失败条数" 0 "${PDFFAIL}"
  PDFEXC=$(jget "${WORK}/pdf.json" "len(d['exceptions'])")
  check "PDF 位置未捕获异常数" 0 "${PDFEXC}"
else
  bad "PDF 位置驱动没产出结果文件（驱动自身出错？见 ${WORK}/pdf.log）"
fi

# ---------------------------------------------------------------------------
echo "== 6. 映射件口令：真浏览器填口令 → 下载映射 → 独立标尺判「文件里没有原值」 =="
#
# 【这一段验的是什么】
#   界面上填了口令之后，交给用户的那个**映射件文件**：
#     · 它是封装件（litemask-sealed-map）；
#     · 它里面**没有原值**（原文 / base64 / hex 三种形式都不许出现）；
#     · 用同一个口令在命令行能还原回来（GUI 封的件，CLI 认得）。
#   三件事缺一不可：
#     · 只看界面提示没用（那只是一句话）；
#     · 只看格式没用（「像」不等于「是」）；
#     · 不验命令行还原，就不知道这份文件对用户还有没有用。
#
# 【标尺是独立的】用的是 tools/verify_sealed_map.py（纯 Python 标准库，
# 不 import 任何产品代码）。拿它去查「没给口令的那份明文映射件」必须报红——
# 那是标尺会咬人的证明；标尺不咬人的这一轮等于没验。
PASS_PHRASE="${E2E_MAP_PASSPHRASE:-口令-e2e-987}"
run_driver_pass() { # $1=标签
  node "$ROOT/tools/browser-cdp.js" \
    --chrome "$CHROME" \
    --url "http://127.0.0.1:${PORT}/" \
    --file "${WORK}/fx/sample.docx" \
    --big-file "${WORK}/fx/big.docx" \
    --long-file "${LONG_FILE:-}" \
    --bad-file "${WORK}/fx/sample.exe" \
    --passphrase "$PASS_PHRASE" \
    --skip-slow \
    --artifact-dir "${WORK}/artifacts/$1" \
    --screenshot "${WORK}/$1.png" \
    > "${WORK}/$1.json" 2> "${WORK}/$1.log"
  return $?
}
run_driver_pass pass
rc=$?
check "口令流程驱动退出码" 0 "${rc}"
if [ "${rc}" -ne 0 ]; then
  echo "---- 驱动的失败明细 ----"
  cat "${WORK}/pass.log"
  echo "----"
fi
PTOT=$(jget "${WORK}/pass.json" "d['total']")
PFAIL=$(jget "${WORK}/pass.json" "d['failed']")
check_ge "口令流程断言条数（那一段都跑完了）" 10 "${PTOT}"
check "口令流程失败条数" 0 "${PFAIL}"

MAP_PATH=$(jget "${WORK}/pass.json" "d['mappingDownload']['path']")
RED_PATH=$(jget "${WORK}/pass.json" "d['redactedDownload']['path']")

# 独立标尺查「文件里没有原值」。
# 原值清单来自样本生成器（tools/gen_browser_fixture.py 的 VALUES），
# 四个值都过了规则校验器，规则不命中时这条断言会安静地变成「测一个不存在的东西」。
if [ -n "${MAP_PATH}" ] && [ -f "${MAP_PATH}" ]; then
  ok "映射件已由浏览器下载到盘上"
  if python3 "$ROOT/tools/verify_sealed_map.py" "$MAP_PATH" \
      --forbid 13800138000 --forbid 11010519491231002X \
      --forbid zhang@example.com --forbid 622202020202020207 \
      > "${WORK}/ruler-pass.log" 2>&1; then
    ok "独立标尺：封装件里没有原值"
  else
    bad "独立标尺报红（文件里可能还有原值）：见 ${WORK}/ruler-pass.log"
    sed 's/^/   /' "${WORK}/ruler-pass.log" | tail -12
  fi
else
  bad "浏览器没把映射件下载到盘上——这一轮的关键证据缺失"
fi

# 负控制：同一把尺子查「没给口令的那份明文映射件」必须报红。
PLAIN_MAP=$(jget "${WORK}/real.json" "d['mappingDownload']['path']")
if [ -n "${PLAIN_MAP}" ] && [ -f "${PLAIN_MAP}" ]; then
  if python3 "$ROOT/tools/verify_sealed_map.py" "$PLAIN_MAP" \
      --forbid 13800138000 > "${WORK}/ruler-plain.log" 2>&1; then
    bad "标尺对明文映射件也报绿 —— 它咬不住，这轮等于没验"
  else
    ok "标尺对明文映射件报红（证明它咬得住）"
  fi
else
  bad "第一轮没下载到映射件，负控制做不了"
fi

# 跨工具：GUI 封的映射件，命令行认不认得。
# 这一条是「界面上的加密有没有真的把文件交到用户手里」的最后一环：
# 映射件封了却还原不回来，对用户来说跟没有映射件一样。
if [ -n "${RED_PATH}" ] && [ -f "${RED_PATH}" ] && [ -n "${MAP_PATH}" ] && [ -f "${MAP_PATH}" ]; then
  export LM_E2E_MAP_PASS="$PASS_PHRASE"
  if prod -jar "$JAR" restore --map "$MAP_PATH" --map-pass-env LM_E2E_MAP_PASS "$RED_PATH" \
      > "${WORK}/restore.log" 2>&1; then
    ok "命令行用同一个口令接受了 GUI 封的映射件"
  else
    bad "命令行还原失败（见 ${WORK}/restore.log）"
    sed 's/^/   /' "${WORK}/restore.log" | tail -8
  fi
  RESTORED="${RED_PATH%.docx}.restored.docx"
  if [ -f "$RESTORED" ]; then
    if python3 -c 'import sys,zipfile
z = zipfile.ZipFile(sys.argv[1])
blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".xml"))
sys.exit(0 if sys.argv[2].encode("utf-8") in blob else 1)' "$RESTORED" 13800138000; then
      ok "还原件里找回了原值（GUI 封的映射件确实能用）"
    else
      bad "还原件里没有原值（见 ${WORK}/restore.log）"
    fi
  else
    bad "命令行没产出还原件（见 ${WORK}/restore.log）"
  fi
else
  bad "没有脱敏件/映射件，跳过命令行还原那一环"
fi

# ---------------------------------------------------------------------------
echo "== 7. 「只保留第 N 处」：同一个值出现 5 次，第 4 次也得选得到 =="
#
# 【这一段验的是什么】
#   主流程那份样本里手机号只出现两次（正文 + 页眉），
#   而界面上「出现处」默认只显示 3 条 —— 两次出现**碰不到**那条限制。
#   这里用一份「同一个值出现 5 次」的样本（sample-many.docx）来验：
#     · 位置清单按**出现序号**铺 5 行（只按位置条数铺的话只有 3 行）；
#     · 默认只显示前 3 条，点「展开全部」后 5 条都露出来；
#     · 第 4 处出现的勾选框存在、序号是 3、说的是「第 4 次出现」；
#     · 提交报文里真的带上了 occurrence=3；
#     · **文件里**只留下了第 4 次出现（独立标尺逐段核对）。
#
# 【标尺是独立的】tools/verify_occurrence_keep.py（纯 Python 标准库，
# 不 import 任何产品代码；令牌长什么样也不写死——它从文件里读出来）。
MANY_FILE="${WORK}/fx/sample-many.docx"
if [ ! -f "${MANY_FILE}" ]; then
  echo "X 没找到多次出现的样本，这一段没法测"
  exit 1
fi

run_occ_driver() { # $1=标签 $2=变异模式
  node "$ROOT/tools/browser-cdp.js" \
    --chrome "$CHROME" \
    --url "http://127.0.0.1:${PORT}/" \
    --occ-only --many-file "${MANY_FILE}" \
    --mutate "$2" \
    --artifact-dir "${WORK}/artifacts/$1" \
    --screenshot "${WORK}/$1.png" \
    > "${WORK}/$1.json" 2> "${WORK}/$1.log"
  return $?
}

run_occ_driver occ none
rc=$?
check "多次出现的驱动退出码" 0 "${rc}"
if [ "${rc}" -ne 0 ]; then
  echo "---- 驱动的失败明细 ----"
  cat "${WORK}/occ.log"
  echo "----"
fi
OTOT=$(jget "${WORK}/occ.json" "d['total']")
OFAIL=$(jget "${WORK}/occ.json" "d['failed']")
# 下限而不是等值：等值会在「我改了断言清单」时静默变红，
# 而这里的意图是「那一段流程都跑完了」——少跑一段就该红。
check_ge "多次出现的断言条数（那一段都跑完了）" 12 "${OTOT}"
check "多次出现的失败条数" 0 "${OFAIL}"
OEXC=$(jget "${WORK}/occ.json" "len(d['exceptions'])")
check "多次出现的未捕获异常数" 0 "${OEXC}"

# 决定性的那一环：文件里到底留下了哪一次出现。
OCC_RED=$(jget "${WORK}/occ.json" "d['redactedDownload']['path']")
if [ -n "${OCC_RED}" ] && [ -f "${OCC_RED}" ]; then
  ok "脱敏件已落盘"
  if python3 "$ROOT/tools/verify_occurrence_keep.py" "${OCC_RED}" \
      --value 13800138000 --keep-index 3 \
      --paragraph-prefix 联系人电话 --expect-occurrences 5 \
      > "${WORK}/ruler-occ.log" 2>&1; then
    ok "独立标尺：文件里只留下了第 4 次出现"
  else
    bad "独立标尺报红（见 ${WORK}/ruler-occ.log）"
    sed 's/^/   /' "${WORK}/ruler-occ.log" | tail -12
  fi
else
  bad "没拿到脱敏件，标尺没法跑——这一段的关键证据缺失"
fi

# ---------------------------------------------------------------------------
echo "== 8. 负控制③：把 app.js 退回「逐处勾选只挂在前 3 条位置上」，这一段必须失败 =="
#
# 变异的是**铺行的依据**（按出现序号铺全部 → 只铺默认显示的那几条），
# 第 4 次及以后的出现于是又没有入口了——正是块 22 要消掉的那条限制。
#
# 【为什么不用「改成按位置条数铺行」那个变异 —— 实测踩到的坑】
#   那是**空操作**：这份样本 occurrences=5，服务端下发的位置说明也是 5 条
#   （20 条上限没碰到），两个数一样 → 变异落盘了、字节也变了，
#   但行为一点没变 → 负控制于是「什么都没坏」，永远绿。
#   → 变异必须换成**真的改行为**的那一个。
#
# 【失败点怎么判 —— 不用「名字里含某个字」这种模糊过滤】
#   那样判会漏：「每一行都挂着勾选框」「展开后 5 条都显示」这些名字里没有那个字，
#   它们同样会因为这个变异而红 → 判成「其余断言受影响」→ 假红。
#   现在改成按 **group** 核对：依赖「第 4 次也有入口」的那一组（group=occ-line）
#   必须**逐条**变红，组外一条都不能红。判据落在「这一条断言本身」上。
run_occ_driver occmut occ-lines-first-three
rc=$?
check "变异后驱动退出码非 0" "1" "${rc}"
OMUT_IN=$(jget "${WORK}/occmut.json" "len([c for c in d['checks'] if not c['ok'] and c.get('group')=='occ-line'])")
OMUT_ALL=$(jget "${WORK}/occmut.json" "len([c for c in d['checks'] if c.get('group')=='occ-line'])")
OMUT_OUT=$(jget "${WORK}/occmut.json" "len([c for c in d['checks'] if not c['ok'] and c.get('group')!='occ-line'])")
OMUT_NAMES=$(jget "${WORK}/occmut.json" "';'.join(c['name'] for c in d['checks'] if not c['ok'])")
check "变异咬住了每一处依赖「第 4 次入口」的断言" "${OMUT_ALL}" "${OMUT_IN}"
check "其余断言不受影响" 0 "${OMUT_OUT}"
if [ "${OMUT_IN}" != "${OMUT_ALL}" ] || [ "${OMUT_OUT}" != "0" ]; then
  echo "---- 负控制实际失败的断言 ----"
  echo "${OMUT_NAMES}"
  echo "----"
fi

# ---------------------------------------------------------------------------
echo "== 9. 收尾 =="
if [ -f "${WORK}/real.json" ]; then
  echo "   正常跑：$(jget "${WORK}/real.json" "d['total']") 条断言，失败 $(jget "${WORK}/real.json" "d['failed']") 条"
fi
echo "== 浏览器端到端：通过 ${pass} / 失败 ${fail} =="

[ "$fail" -eq 0 ] || exit 1
exit 0
