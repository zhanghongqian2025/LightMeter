#!/usr/bin/env bash
# M2-a 端到端：走打包后的 fat jar，真起一个本地服务，用 curl 从外部打一遍。
#
# 与单测的分工：
#   单测 LocalServerApiTest 用裸 socket，验的是「服务端这套逻辑对不对」；
#   这里验的是「打好的 jar 真的能起、真的只绑回环、真的挡得住 curl」。
#   jar 打包这一步会换掉 mainClass 与类加载路径，只测 target/classes 覆盖不到它。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 打包用的 JDK 与跑产品用的 java 是**两件事**，必须能分开指定。
#
# 为什么分开（M3-4 踩出来的）：M4 交付的是 jlink 定制运行时，而
# 「这个模块集够不够用」只能靠**整套端到端在那个运行时上跑一遍**来证明。
# 跑四条手工命令不算证明——OOXML 那几个类、Jackson、ONNX Runtime 都可能
# 静态引用别的模块，而静态引用要等到那条路径真的被加载才抛
# NoClassDefFoundError。本轮实测就是漏了 java.logging：
# jdeps 报告只需要 java.base/java.xml/jdk.httpserver，全绿的模块集，
# 真加载模型包时 ai.onnxruntime.OrtEnvironment.<clinit> 要 java.util.logging.Logger。
#
# 用法：LITEMASK_RUN_JAVA=/path/to/jlink-rt/bin/java ./tools/e2e-serve.sh
# jar 仍然用完整 JDK 打，只有跑产品那一步换成精简运行时。
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="${JAVA_HOME}/bin:${PATH}"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
if [ "${LITEMASK_RUN_JAVA:-}" != "" ]; then
  echo "运行产品用的 java：${RUN_JAVA}（打包仍用 ${JAVA_HOME}）"
fi
MVN="$MVN_BIN -B -o -Dstyle.color=never"

PORT="${E2E_PORT:-18931}"
WORK="$(mktemp -d)"
JAR="$ROOT/target/desens-core.jar"
SRV_PID=""
SRV_PID2=""
CRASH_PID=""
RESTART_PID=""
pass=0; fail=0

ok()   { echo "PASS $1"; pass=$((pass+1)); }
bad()  { echo "FAIL $1"; fail=$((fail+1)); }
check(){ # $1=名称 $2=期望 $3=实际
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 $2，实际 $3）"; fi
}

# 运行形态由 e2e-common 决定（默认无模型 = pkg 交付形态；LITEMASK_E2E_NER=<目录> 则带模型）。
# 这一支以前随开发机装没装 NER 模型而变：无模型全绿，装了 v9 就红
# （scan 的 hits 顺序变了，脚本按「第 1 个 hit 的 ruleKey」取排除项就取错了）。
. "$ROOT/tools/e2e-common.sh"
e2e_form_banner

cleanup() {
  [ -n "${RESTART_PID:-}" ] && kill "$RESTART_PID" 2>/dev/null
  [ -n "${CRASH_PID:-}" ] && kill -9 "$CRASH_PID" 2>/dev/null
  [ -n "${SRV_PID2:-}" ] && kill "$SRV_PID2" 2>/dev/null
  [ -n "${SRV_PID}" ] && kill "$SRV_PID" 2>/dev/null
  wait "$SRV_PID" 2>/dev/null
  # mktemp -d 是绝对路径，rm 不会被相对路径的网关规则拦
  [ -n "${WORK:-}" ] && rm_rf "${WORK}"
}
trap cleanup EXIT

echo "== 构建 fat jar =="
$MVN -f "$ROOT/pom.xml" package -DskipTests -q 2>&1 | tail -3
[ -f "$JAR" ] || { echo "X 没有产出 ${JAR}，实验无效"; exit 1; }
echo "   $(wc -c < "$JAR") 字节"

echo "== 启动本地服务（端口 ${PORT}）=="
# 注意用 "${PROD_CMD[@]}" 而不是 prod：后台启动必须让 $! 拿到**真正进程的 PID**，
# 否则下面 kill "$SRV_PID" 打的是子 shell，服务收不到 SIGTERM，
# 「停止后工作目录整棵删除」那条就会红。理由见 tools/e2e-common.sh 里的探针注释。
"${PROD_CMD[@]}" -jar "$JAR" serve \
  --port "$PORT" --workspace "${WORK}/ws" --no-browser --print-token \
  > "${WORK}/server.log" 2>&1 &
SRV_PID=$!

# 等健康检查通过。端口占用时服务会直接退出——那本身是第 5 项要测的，别混进来。
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
TOKEN=$(grep -o '会话令牌: .*' "${WORK}/server.log" | head -1 | sed 's/会话令牌: //')
[ -n "$TOKEN" ] || { echo "X 没拿到会话令牌"; exit 1; }

# 从这里起所有检查都记 pass/fail，最后统一判定，不中途退出——
# 中途退出会让 trap 里的清理掩盖掉「前面哪几项已经过了」。
# ---------------------------------------------------------------------------

echo "== 1. 本机请求放行 =="
code=$(curl -s -o "${WORK}/h.json" -w '%{http_code}' "http://127.0.0.1:${PORT}/api/health")
check "GET /api/health 返回 200" 200 "${code}"
grep -q '"ok":true' "${WORK}/h.json" && ok "响应含 ok=true" || bad "响应缺 ok=true"

echo "== 2. 伪造 Host 被拒（DNS rebinding）=="
for evil in "evil.com" "localhost.evil.com" "127.0.0.1.evil.com"; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -H "Host: ${evil}:${PORT}" \
    "http://127.0.0.1:${PORT}/api/health")
  check "Host=${evil} 被拒" 403 "${code}"
done

echo "== 3. 端口不符被拒 =="
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Host: 127.0.0.1:$((PORT+1))" \
  "http://127.0.0.1:${PORT}/api/health")
check "端口 +1 被拒" 403 "${code}"

echo "== 4. 写操作：令牌缺失/错误/跨源 =="
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "Host: 127.0.0.1:${PORT}" "http://127.0.0.1:${PORT}/api/scan")
check "无令牌被拒" 403 "${code}"

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "Host: 127.0.0.1:${PORT}" -H "X-Litemask-Token: ${TOKEN}xx" \
  "http://127.0.0.1:${PORT}/api/scan")
check "错令牌被拒" 403 "${code}"

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "Host: 127.0.0.1:${PORT}" -H "X-Litemask-Token: ${TOKEN}" \
  -H "Origin: https://evil.com" "http://127.0.0.1:${PORT}/api/scan")
check "跨源写被拒（即使令牌正确）" 403 "${code}"

# 三者齐备但没给 Content-Type：应当由**处理器**抱怨请求体类型（400），
# 而不是被鉴权拦下（403）。两者必须分得开，否则新接口把鉴权接错也没人发现。
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "Host: 127.0.0.1:${PORT}" -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/scan")
check "三者齐备抵达处理器（400 = 鉴权过、类型不对）" 400 "${code}"

code=$(curl -s -o "${WORK}/scan.json" -w '%{http_code}' -X POST \
  -H "Host: 127.0.0.1:${PORT}" -H "X-Litemask-Token: ${TOKEN}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-binary 'x' "http://127.0.0.1:${PORT}/api/scan")
check "表单类型被拒（不能只靠令牌一道防线）" 400 "${code}"

echo "== 5. 响应里不得有任何 CORS 头 =="
curl -s -D "${WORK}/hdr.txt" -o /dev/null "http://127.0.0.1:${PORT}/api/health"
if grep -qi 'access-control-allow' "${WORK}/hdr.txt"; then
  bad "响应里出现了 CORS 头"
  grep -i 'access-control-allow' "${WORK}/hdr.txt"
else
  ok "无 CORS 头"
fi
for want in 'Cache-Control: no-store' 'X-Content-Type-Options: nosniff' 'Referrer-Policy: no-referrer'; do
  if grep -qi "^${want}" "${WORK}/hdr.txt"; then ok "安全头 ${want}"; else bad "缺安全头 ${want}"; fi
done

echo "== 6. 令牌只在页面正文里，不在 URL =="
curl -s "http://127.0.0.1:${PORT}/" -o "${WORK}/index.html"
grep -q "${TOKEN}" "${WORK}/index.html" && ok "首页注入了令牌" || bad "首页没有令牌"
grep -q '__LITEMASK_TOKEN__' "${WORK}/index.html" && bad "占位符没被替换" || ok "占位符已替换"

echo "== 7. OPTIONS 不应答预检 =="
code=$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS \
  -H "Host: 127.0.0.1:${PORT}" "http://127.0.0.1:${PORT}/api/scan")
check "OPTIONS 返回 405" 405 "${code}"

echo "== 8. 只绑回环：局域网地址连不上 =="
LAN=""
for dev in $(ifconfig -l 2>/dev/null); do
  ip=$(ifconfig "$dev" 2>/dev/null | awk '/inet /{print $2}' | grep -v '^127\.' | head -1)
  if [ -n "$ip" ]; then LAN="$ip"; break; fi
done
if [ -z "$LAN" ]; then
  echo "   （无可用局域网地址，跳过这条：离线环境下不伪造通过）"
else
  if curl -s -o /dev/null --max-time 2 "http://${LAN}:${PORT}/api/health" 2>/dev/null; then
    bad "局域网地址 ${LAN}:${PORT} 可访问 —— 服务对全网开放"
  else
    ok "局域网地址 ${LAN}:${PORT} 连不上"
  fi
fi

echo "== 9. 界面三步：上传 → 排除 → 应用 → 下载 =="

# 端到端要验的是「上传→排除→应用→下载」这条链路本身，不挑格式。
# txt 走的是纯文本管线，接口上同样是 Format.TXT，省掉拼 zip 的功夫；
# 五种格式各自的管线由单测与各自的 e2e 脚本负责。
cat > "${WORK}/probe.txt" <<'TXT'
甲方联系人 张三 电话 13800138000
乙方联系人 李四 电话 13900139000
身份证 11010519491231002X
TXT

UP_HDRS=(-H "X-Litemask-Token: ${TOKEN}" \
         -H "Content-Type: application/octet-stream" \
         -H "X-Filename: probe.txt")

code=$(curl -s -o "${WORK}/scan.json" -w '%{http_code}' \
  -X POST --data-binary "@${WORK}/probe.txt" \
  "${UP_HDRS[@]}" "http://127.0.0.1:${PORT}/api/scan")
check "上传并扫描返回 200" 200 "${code}"
JOB=$(sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p' "${WORK}/scan.json" | head -1)
[ -n "$JOB" ] && ok "拿到 jobId" || { bad "没拿到 jobId：$(cat "${WORK}/scan.json")"; }

grep -q '"stage":"scanned"' "${WORK}/scan.json" \
  && ok "stage=scanned" || bad "stage 不是 scanned"
grep -q '13800138000' "${WORK}/scan.json" \
  && ok "命中清单带回了原值（界面靠它显示）" \
  || bad "命中清单没带原值"
grep -q '"hasLocators":true' "${WORK}/scan.json" \
  && ok "txt 命中带位置信息（偏移）" \
  || bad "txt 命中缺位置信息"

# 排除**手机号**那一行命中的全部成员。
#
# 【这一段以前按字面 ruleKey="phone" 取命中 —— 带 NER 模型时必崩，崩了 12 条断言】
#   服务端会把**原文里紧挨着、被不同规则分别认领**的几处命中**归组成一行**，
#   行的 ruleKey 是合并后的（形如 "ner_person + phone"），
#   真正的子规则在 members 里。
#   带模型时的实测：模型把手机号的「1」和「8000」判成了人名，
#   手机号规则只认领中间的 "380013" → 那一行的 ruleKey 是 "ner_person + phone"，
#   members 才是 ner_person/1、phone/380013、ner_person/8000 三条。
#   → 按字面 "phone" 找命中，一行都找不到 → 排除项构造不出来 → apply 报 400 →
#     后面下载、映射、还原全是它的连坐（12 条红的真正源头只有 1 条）。
# 现在改成：**按 members 找**那一行，并把**该行的成员逐个展开**后发排除项 ——
#   这正是界面上的做法（src/main/resources/web/app.js：勾选一行 → 按 members 展开）。
#   → 端到端从此测的是**界面真正在用的那个契约**，而不是界面并不用的简化契约。
#   → 两种形态都成立：无模型时该行只有一个成员，带模型时是三个成员。
#   → 成员清单由 python 写进文件再读回来：JSON 里有空格，不能用 read 切字段。
# 【为什么用「写文件 + python3 -c」而不是「进程替换里套 heredoc」】
#   实测（本机 bash）：**进程替换 `<( … )` 里的 heredoc 正文会经过 brace expansion** ——
#   正文里只要有 `{…,…}`（带逗号的花括号），就会被展开成两个词，
#   正文因此被拆成两段，python 收到的是语法错的东西，
#   而报错指向 python，根因却完全不在 python。
#   → 这段 python 的正文里就有 `{"ruleKey": …, "original": …}`，正好踩中。
#   → 这里改成：python3 -c '…' 直接输出到文件，shell 再从文件读。
#     没有进程替换，没有 heredoc，结果确定。
#   （同一族的坑：work/ 里有三个脚本也用「进程替换套 heredoc」，
#     它们的正文里没有带逗号的花括号，所以一直没炸 —— 但那是运气，不是安全。）
FIRST_RULE=""
FIRST_VAL=""
N_REFS=0
python3 -c '
import json, sys
hits = json.load(open(sys.argv[1])).get("hits", [])
row = None
for h in hits:
    if any(m.get("ruleKey") == "phone" for m in (h.get("members") or [])):
        row = h
        break
refs = []
if row is not None:
    refs = [{"ruleKey": m.get("ruleKey"), "original": m.get("original")}
            for m in (row.get("members") or [])]
with open(sys.argv[2], "w", encoding="utf-8") as f:
    json.dump(refs, f, ensure_ascii=False)
if row is not None:
    print(row.get("ruleKey", ""), row.get("original", ""), len(refs), sep="\t")
' "${WORK}/scan.json" "${WORK}/excluded.json" > "${WORK}/picked.txt"
IFS=$'\t' read -r FIRST_RULE FIRST_VAL N_REFS < "${WORK}/picked.txt"
[ -n "$FIRST_VAL" ] && ok "解析出手机号命中（${FIRST_RULE} / ${FIRST_VAL}，展开 ${N_REFS} 个成员）" \
                    || bad "解析不出手机号命中"

cat > "${WORK}/apply.json" <<JSON
{"jobId":"${JOB}","excluded":$(cat "${WORK}/excluded.json")}
JSON
code=$(curl -s -o "${WORK}/applied.json" -w '%{http_code}' \
  -X POST -H "X-Litemask-Token: ${TOKEN}" -H "Content-Type: application/json" \
  --data-binary "@${WORK}/apply.json" "http://127.0.0.1:${PORT}/api/apply")
check "应用排除项返回 200" 200 "${code}"
# 计数必须与**实际发出的排除项条数**一致：发了几条，服务端就该报几条。
# 口径不能写死「1 条」——带模型时那一行展开成三个成员。
if [ "${N_REFS}" -ge 1 ] && grep -q "\"excluded\":${N_REFS}," "${WORK}/applied.json"; then
  ok "如实报出排除了 ${N_REFS} 条"
else
  bad "excluded 计数不对（发出 ${N_REFS} 条）：$(cat "${WORK}/applied.json")"
fi

code=$(curl -s -o "${WORK}/final.txt" -w '%{http_code}' \
  -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/download?jobId=${JOB}")
check "下载返回 200" 200 "${code}"

# 判据在下载回来的文件内容上，不看接口自己报的命中数
if grep -q "${FIRST_VAL}" "${WORK}/final.txt"; then
  ok "被排除的值原样保留在成品里"
else
  bad "被排除的值没留在成品里：$(cat "${WORK}/final.txt")"
fi
if grep -q '13900139000\|11010519491231002X' "${WORK}/final.txt"; then
  bad "未排除的值仍以原样留在成品里：$(cat "${WORK}/final.txt")"
else
  ok "未排除的值确已脱敏"
fi

echo "== 9b. 位置级排除：只保留第 N 次 =="

cat > "${WORK}/probe2.txt" <<'TXT'
第一处 13800138000
第二处 13800138000
第三处 13800138000
TXT
code=$(curl -s -o "${WORK}/scan2.json" -w '%{http_code}' \
  -X POST --data-binary "@${WORK}/probe2.txt" \
  "${UP_HDRS[@]}" "http://127.0.0.1:${PORT}/api/scan")
JOB2=$(sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p' "${WORK}/scan2.json" | head -1)
check "第二次上传返回 200" 200 "${code}"
grep -q '"occurrences":3' "${WORK}/scan2.json" \
  && ok "同一值报了 3 处出现" \
  || bad "出现次数不对：$(cat "${WORK}/scan2.json")"

cat > "${WORK}/apply2.json" <<JSON
{"jobId":"${JOB2}","excluded":[{"ruleKey":"phone","original":"13800138000","occurrence":1}]}
JSON
code=$(curl -s -o "${WORK}/applied2.json" -w '%{http_code}' \
  -X POST -H "X-Litemask-Token: ${TOKEN}" -H "Content-Type: application/json" \
  --data-binary "@${WORK}/apply2.json" "http://127.0.0.1:${PORT}/api/apply")
check "按第 1 次排除返回 200" 200 "${code}"

curl -s -o "${WORK}/final2.txt" -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/download?jobId=${JOB2}" >/dev/null
n=$(grep -o '13800138000' "${WORK}/final2.txt" | wc -l | tr -d ' ')
check "成品里恰好剩 1 处原值（其余两处已脱）" 1 "${n}"
# 【为什么不能按「第二处 13800138000」这串文字判断】
#   带 NER 模型时，"第二处" 会被判成机构名给掩掉，
#   成品里那一行是 "<机构名1> 13800138000" —— 手机号本身**确实**是第 2 次那一处，
#   但按周边文字 grep 会误判成「保留错了」，红得没有道理。
#   → 改成按**行号**判断：probe2.txt 每行一处，第 2 行就是「第 1 次（第二处）」。
#     这条判据盯的仍然是「保留的是哪一次」，只是不再依赖周边文字有没有被掩。
kept_line=$(grep -n '13800138000' "${WORK}/final2.txt" | head -1 | cut -d: -f1)
if [ "${kept_line:-}" = "2" ]; then
  ok "保留的正是第 1 次（第二处）那一处"
else
  bad "保留的不是第 1 次那一处（原值出现在第 ${kept_line:-?} 行）：$(cat "${WORK}/final2.txt")"
fi

echo "== 9c. 排除项对不上扫描结果时拒绝出件 =="

# 必须用**另一个任务**：JOB2 在 9b 已经 apply 过了，同一任务重复 apply 会先被
# 「已经应用过」挡掉，压根走不到序号校验那一行。测 400 却测到另一个 400，
# 就成了一条什么都证明不了的断言。
cat > "${WORK}/probe3.txt" <<'TXT'
唯一一处 13800138000
TXT
curl -s -o "${WORK}/scan3.json" \
  -X POST --data-binary "@${WORK}/probe3.txt" \
  "${UP_HDRS[@]}" "http://127.0.0.1:${PORT}/api/scan" >/dev/null
JOB3=$(sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p' "${WORK}/scan3.json" | head -1)

cat > "${WORK}/apply3.json" <<JSON
{"jobId":"${JOB3}","excluded":[{"ruleKey":"phone","original":"13800138000","occurrence":99}]}
JSON
code=$(curl -s -o "${WORK}/applied3.json" -w '%{http_code}' \
  -X POST -H "X-Litemask-Token: ${TOKEN}" -H "Content-Type: application/json" \
  --data-binary "@${WORK}/apply3.json" "http://127.0.0.1:${PORT}/api/apply")
check "序号越界的排除项被拒（400）" 400 "${code}"
# 顺带钉住「被拒的请求不留下可下载的产物」：只看到 400 不够，
# 还得确认那个 jobId 真的取不到东西。
code=$(curl -s -o /dev/null -w '%{http_code}' \
  -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/download?jobId=${JOB3}")
check "被拒的任务没有可下载产物" 400 "${code}"

echo "== 9d. 界面静态资源：首页含令牌、脚本同源、无内联脚本 =="

curl -s -o "${WORK}/index.html" "http://127.0.0.1:${PORT}/"
grep -q "${TOKEN}" "${WORK}/index.html" && ok "首页正文注入了令牌" || bad "首页没注入令牌"
grep -q '<script src="/app.js">' "${WORK}/index.html" \
  && ok "脚本同源外链（对应 CSP 的 script-src 'self'）" \
  || bad "脚本不是同源外链"
grep -q 'onclick=' "${WORK}/index.html" && bad "首页出现内联事件属性" || ok "首页无内联事件属性"

code=$(curl -s -o "${WORK}/app.js" -w '%{http_code}' "http://127.0.0.1:${PORT}/app.js")
check "GET /app.js 返回 200" 200 "${code}"
# 剥掉注释再查：文件头的纪律说明里就写着「绝不拼进 innerHTML」，
# 不剥注释的话这条永远红，而且红得没有意义（代码里其实没有）。
python3 - "${WORK}/app.js" <<'PY' > "${WORK}/app.code.js"
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', ' ', s, flags=re.S)
s = re.sub(r'//[^\n]*', ' ', s)
print(s)
PY
if grep -q 'innerHTML\|outerHTML\|document\.write' "${WORK}/app.code.js"; then
  bad "脚本代码里出现 innerHTML/outerHTML/document.write（存储型 XSS 面）"
else
  ok "脚本代码里没有 HTML 拼接面"
fi
grep -q 'X-Litemask-Token' "${WORK}/app.js" \
  && ok "脚本通过自定义头带令牌" \
  || bad "脚本没用自定义头带令牌"

echo "== 9e. 映射出口：GUI 产出的映射件能直接喂给命令行还原 =="

# 这一段验的是本工具最重要的承诺：**脱敏件能被还原回去**。
# 此前映射只活在服务端内存里，进程一退出就没了——界面显示「脱敏成功」，
# 而那份文件实际上永久不可逆，界面上看不出任何异常。
#
# 判据在**文件内容**上：命令行拿 GUI 的映射件去还原，还原件必须与原文逐字一致。
# 不看接口自报的「还原了 N 处」——那是接口自己说的。
code=$(curl -s -o "${WORK}/map.json" -w '%{http_code}' \
  -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/mapping?jobId=${JOB}")
check "下载映射件返回 200" 200 "${code}"
grep -q '"mapping"' "${WORK}/map.json" \
  && ok "映射件里有 mapping 段" \
  || bad "映射件里没有 mapping 段: $(head -c 300 "${WORK}/map.json")"

# 映射里全是原值：没有令牌就拿不到
code=$(curl -s -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:${PORT}/api/mapping?jobId=${JOB}")
check "无令牌下载映射被拒" 403 "${code}"

# 没应用过的任务没有映射
code=$(curl -s -o /dev/null -w '%{http_code}' \
  -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/mapping?jobId=${JOB3}")
check "没应用过的任务拿不到映射" 400 "${code}"

# 闭环：GUI 的映射件 → 命令行还原 → 与原文逐字一致
# 用 9 里那份成品（first phone 被排除，其余已脱敏）作输入。
if prod -jar "$JAR" restore "${WORK}/final.txt" --map "${WORK}/map.json" >/dev/null 2>&1 \
   && [ -f "${WORK}/final.restored.txt" ]; then
  if cmp -s "${WORK}/probe.txt" "${WORK}/final.restored.txt"; then
    ok "GUI 的映射件喂给命令行，还原后与原文逐字一致"
  else
    bad "命令行还原后与原文不一致"
    diff "${WORK}/probe.txt" "${WORK}/final.restored.txt" | head -10
  fi
else
  bad "命令行没有产出还原件（找的是 ${WORK}/final.restored.txt）"
fi

echo "== 9f. 界面里的还原 =="

code=$(curl -s -D "${WORK}/rh.txt" -o "${WORK}/restored.txt" -w '%{http_code}' \
  -X POST -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/restore?jobId=${JOB}")
check "还原返回 200" 200 "${code}"
if grep -qi '^x-litemask-restored: [1-9]' "${WORK}/rh.txt"; then
  ok "响应头报出还原处数"
else
  bad "响应头没有报出还原处数：$(grep -i 'x-litemask' "${WORK}/rh.txt" | tr '\n' ' ')"
fi
if grep -qi '^x-litemask-notice:' "${WORK}/rh.txt"; then
  ok "响应头带出「还原的是文本内容不是文件字节」的提醒"
else
  bad "响应头没有提醒：用户会以为还原件与原件是同一个文件"
fi
if cmp -s "${WORK}/probe.txt" "${WORK}/restored.txt"; then
  ok "还原件与原文逐字一致"
else
  bad "还原件与原文不一致"
  diff "${WORK}/probe.txt" "${WORK}/restored.txt" | head -10
fi

# 还原件里不该再有令牌
if grep -q '<手机' "${WORK}/restored.txt"; then
  bad "还原件里还留着令牌：$(cat "${WORK}/restored.txt")"
else
  ok "还原件里没有令牌"
fi

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "http://127.0.0.1:${PORT}/api/restore?jobId=${JOB}")
check "无令牌还原被拒" 403 "${code}"

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/restore?jobId=${JOB3}")
check "没应用过的任务不能还原" 400 "${code}"

echo "== 9g. 长任务：异步提交 → 轮询进度 → 取消 =="

# 界面上「扫描中」这三个字底下是一条真的链路：
# 提交（202）→ 轮询（GET /api/task）→ 终态带结果。
# 这里走打包后的 jar 打一遍，验的是线协议，不是服务端内部。

task_field() { # $1=文件 $2=字段名
  python3 - "$1" "$2" <<'PY'
import json, sys
try:
    j = json.load(open(sys.argv[1]))
except Exception:
    print("")
    raise SystemExit(0)
v = j
for part in sys.argv[2].split("."):
    if isinstance(v, dict):
        v = v.get(part)
    else:
        v = None
if v is None:
    print("")
elif isinstance(v, bool):
    print("true" if v else "false")
else:
    print(v)
PY
}

wait_task() { # $1=任务号 $2=终态报文写到这里
  local tid="$1" out="$2" i st
  for i in $(seq 1 200); do
    curl -s -o "${out}" -H "X-Litemask-Token: ${TOKEN}" \
      "http://127.0.0.1:${PORT}/api/task?taskId=${tid}"
    st=$(task_field "${out}" status)
    [ "$st" = "running" ] || { echo "${st}"; return 0; }
    sleep 0.1
  done
  echo "timeout"
}

code=$(curl -s -o "${WORK}/task.json" -w '%{http_code}' \
  -X POST --data-binary "@${WORK}/probe.txt" \
  "${UP_HDRS[@]}" "http://127.0.0.1:${PORT}/api/task-scan")
check "提交扫描任务返回 202（不等它跑完）" 202 "${code}"
TASK=$(task_field "${WORK}/task.json" taskId)
[ -n "$TASK" ] && ok "拿到任务号" || bad "没拿到任务号：$(cat "${WORK}/task.json")"
check "提交后状态是 running" "running" "$(task_field "${WORK}/task.json" status)"

ST=$(wait_task "${TASK}" "${WORK}/taskdone.json")
check "轮询到任务完成" "done" "${ST}"
[ "${ST}" = "done" ] || echo "   扫描任务快照：$(cat "${WORK}/taskdone.json")"
check "终态带回了作业号" "true" "$([ -n "$(task_field "${WORK}/taskdone.json" jobId)" ] && echo true || echo false)"
check "终态的结果里有命中清单" "true" \
  "$([ "$(task_field "${WORK}/taskdone.json" result.summary.occurrences)" = "$(task_field "${WORK}/scan.json" summary.occurrences)" ] && echo true || echo false)"
check "进度至少报过一次" "true" \
  "$([ "$(task_field "${WORK}/taskdone.json" done)" -ge 1 ] 2>/dev/null && echo true || echo false)"
check "总量报 -1（不糊一个假百分比）" "-1" "$(task_field "${WORK}/taskdone.json" total)"

# 异步的应用任务
#
# 【这里曾经写错过一次】用的是 ${TASK}（任务号）而不是 ${JOB}（作业号）：
# 两者都是十六进制串，长得几乎一样，但服务端要的是作业号。
# 写错的后果是任务失败（作业不存在）——
# 而失败信息在任务快照的 error 字段里，脚本只看 status 就只能看到「failed」，
# 看不出「是作业号给错了」。所以这里先把作业号单独取出来，并断言它非空。
JOB=$(task_field "${WORK}/taskdone.json" jobId)
[ -n "$JOB" ] && ok "终态里拿到了作业号" || bad "终态里没有作业号"
cat > "${WORK}/taskapply.json" <<JSON
{"jobId":"${JOB}","excluded":[]}
JSON
code=$(curl -s -o "${WORK}/taskapply-res.json" -w '%{http_code}' \
  -X POST -H "X-Litemask-Token: ${TOKEN}" -H "Content-Type: application/json" \
  --data-binary "@${WORK}/taskapply.json" "http://127.0.0.1:${PORT}/api/task-apply")
check "提交应用任务返回 202" 202 "${code}"
TASK2=$(task_field "${WORK}/taskapply-res.json" taskId)
ST2=$(wait_task "${TASK2}" "${WORK}/taskapply-done.json")
check "应用任务完成" "done" "${ST2}"
# 失败时把整份快照打出来：只看 status 的话，failed 背后的原因（作业号给错？
# 排除项对不上？）根本看不出来，只能干瞪眼。
[ "${ST2}" = "done" ] || echo "   应用任务快照：$(cat "${WORK}/taskapply-done.json")"
check "应用完成后给出了映射件名字" "true" \
  "$([ -n "$(task_field "${WORK}/taskapply-done.json" result.mappingName)" ] && echo true || echo false)"

# 未知任务号：查与取消都应当 400
code=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/task?taskId=根本没有这个任务号")
check "查不存在的任务返回 400" 400 "${code}"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/task-cancel?taskId=根本没有这个任务号")
check "取消不存在的任务返回 400" 400 "${code}"

# 任务结果里有原值（命中清单），所以查任务也要令牌
code=$(curl -s -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:${PORT}/api/task?taskId=${TASK}")
check "无令牌查任务被拒" 403 "${code}"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "http://127.0.0.1:${PORT}/api/task-cancel?taskId=${TASK}")
check "无令牌取消任务被拒" 403 "${code}"

# 方法不对：路径存在但方法错
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/task")
check "对只读任务接口发 POST 返回 405" 405 "${code}"

# 取消：交一份大文档上去，让它跑得足够久，取消才真的落在管线中途。
# 判据不是「取消接口回了 200」——那只是受理；要看的是
# **任务最终是 canceled，而且磁盘上没有多出任何产物**。
# 纯普通文字：**不放**任何能命中的标识符。
# 放了的话会生成十几万条替换明细，既慢又占内存，
# 而这里要的只是「让扫描跑得足够久，久到取消能落在管线中途」。
python3 - "${WORK}/big.txt" <<'PY'
import sys
with open(sys.argv[1], "w", encoding="utf-8") as f:
    for i in range(120000):
        f.write("第%d行 这是一段用来把扫描拖慢的普通文字，里面没有任何可命中的标识符。\n" % i)
PY
BEFORE=$(curl -s -H "X-Litemask-Token: ${TOKEN}" "http://127.0.0.1:${PORT}/api/health" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("activeJobs",-1))')
code=$(curl -s -o "${WORK}/bigtask.json" -w '%{http_code}' \
  -X POST --data-binary "@${WORK}/big.txt" \
  -H "X-Litemask-Token: ${TOKEN}" -H "Content-Type: application/octet-stream" \
  -H "X-Filename: big.txt" "http://127.0.0.1:${PORT}/api/task-scan")
check "提交大文档任务返回 202" 202 "${code}"
BIGTASK=$(task_field "${WORK}/bigtask.json" taskId)
code=$(curl -s -o "${WORK}/bigcancel.json" -w '%{http_code}' -X POST \
  -H "X-Litemask-Token: ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/task-cancel?taskId=${BIGTASK}")
check "取消请求被受理" 200 "${code}"
check "取消回的是 canceling（受理，不是终态）" "canceling" "$(task_field "${WORK}/bigcancel.json" status)"
ST3=$(wait_task "${BIGTASK}" "${WORK}/bigtask-done.json")
check "被取消的任务终态是 canceled" "canceled" "${ST3}"
check "被取消的任务没有留下作业号（也就没有留下任何文件）" "" \
  "$(task_field "${WORK}/bigtask-done.json" jobId)"
AFTER=$(curl -s -H "X-Litemask-Token: ${TOKEN}" "http://127.0.0.1:${PORT}/api/health" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("activeJobs",-1))')
check "取消后作业数没有增加" "${BEFORE}" "${AFTER}"

# 界面上真的走的是异步那条路（打的是 jar 里的静态资源，不是源码）
grep -q '/api/task-scan' "${WORK}/app.js" \
  && ok "界面走异步任务接口" \
  || bad "界面没走异步任务接口"
grep -q '"/api/scan"' "${WORK}/app.code.js" \
  && bad "界面还在调同步接口（长任务上会装死）" \
  || ok "界面不再调同步接口"

echo "== 9h. 上传被拒：提交时同步 400，不是先收下再异步失败 =="

# 界面上的「上传」是 POST /api/task-scan。以前不支持的后缀是**先收下**、
# 任务跑起来才失败——用户等了一圈才知道白传了；同步接口的超限更是返 500
# （把「你发错了」说成「服务坏了」）。现在必须在提交时就拒。
#
# 判据不看错误文案（文案会变），看三件事：
#   ① 状态码是 400（不是 202「已受理」，也不是 500）；
#   ② 响应里**没有 taskId**（有 taskId 就说明它被受理了）；
#   ③ 会话目录里的文件数没变（被拒的上传连磁盘都不该占）。
#
# 为什么要第 ③ 条：文案和状态码是「服务端说了什么」，
# 文件数是「磁盘上真的发生了什么」——只信前者的话，
# 「先落盘再删掉」这种形状（占了盘又删）会被放过。

SESSION_DIR2=$(ls -d "${WORK}"/ws/sessions/*/ 2>/dev/null | head -1)
count_files() { # $1=目录
  [ -d "$1" ] || { echo 0; return; }
  find "$1" -type f 2>/dev/null | wc -l | tr -d ' '
}
BEFORE_N=$(count_files "${SESSION_DIR2}")

printf 'MZ fake exe payload' > "${WORK}/evil.exe"
code=$(curl -s -o "${WORK}/bad-ext.json" -w '%{http_code}' -X POST \
  --data-binary "@${WORK}/evil.exe" \
  -H "X-Litemask-Token: ${TOKEN}" -H "Content-Type: application/octet-stream" \
  -H "X-Filename: evil.exe" "http://127.0.0.1:${PORT}/api/task-scan")
check "不支持的后缀：异步接口提交时同步 400" 400 "${code}"
check "被拒的上传没有 taskId（说明压根没被受理）" "" "$(task_field "${WORK}/bad-ext.json" taskId)"
check "被拒的上传明确说了不支持" "true" \
  "$(grep -q '不支持' "${WORK}/bad-ext.json" && echo true || echo false)"

code=$(curl -s -o "${WORK}/bad-ext-sync.json" -w '%{http_code}' -X POST \
  --data-binary "@${WORK}/evil.exe" \
  -H "X-Litemask-Token: ${TOKEN}" -H "Content-Type: application/octet-stream" \
  -H "X-Filename: evil.exe" "http://127.0.0.1:${PORT}/api/scan")
check "同步接口同样返 400（以前超限那类是 500）" 400 "${code}"

AFTER_N=$(count_files "${SESSION_DIR2}")
check "被拒的上传没有在磁盘上留下任何东西" "${BEFORE_N}" "${AFTER_N}"

# 对照组：同一套请求，只把后缀换成白名单里的，必须照样受理。
# 没有这条，上面那几条有可能是因为「请求本身就发错了」而红。
code=$(curl -s -o "${WORK}/good-ext.json" -w '%{http_code}' -X POST \
  --data-binary "@${WORK}/evil.exe" \
  -H "X-Litemask-Token: ${TOKEN}" -H "Content-Type: application/octet-stream" \
  -H "X-Filename: ok.txt" "http://127.0.0.1:${PORT}/api/task-scan")
check "对照组：白名单里的后缀照样受理（202）" 202 "${code}"
[ -n "$(task_field "${WORK}/good-ext.json" taskId)" ] \
  && ok "对照组：拿到了任务号" \
  || bad "对照组：没拿到任务号：$(cat "${WORK}/good-ext.json")"

echo "== 9h-2. 上传超限：提交时同步 400 =="

# 超限这条要在**真实服务**上验，就得有一个小于 256MB 的上限——
# 否则只能真传 256MB+1（几百 MB 的临时文件，而且服务端会掐断连接，
# 那种传输噪声会盖住要验的行为）。所以起第二个服务，
# 用 --max-upload-bytes 把上限调小，走的是同一段判定代码。
SMALL_PORT=$((PORT+1))
"${PROD_CMD[@]}" -jar "$JAR" serve \
  --port "$SMALL_PORT" --workspace "${WORK}/ws-small" --no-browser --print-token \
  --max-upload-bytes 4096 \
  > "${WORK}/server-small.log" 2>&1 &
SRV_PID2=$!
SMALL_READY=""
for _ in $(seq 1 60); do
  if ! kill -0 "$SRV_PID2" 2>/dev/null; then break; fi
  if curl -s -o /dev/null "http://127.0.0.1:${SMALL_PORT}/api/health" 2>/dev/null; then
    SMALL_READY=yes; break
  fi
  sleep 0.25
done
if [ -z "$SMALL_READY" ]; then
  bad "小上限服务没起来，日志：$(cat "${WORK}/server-small.log")"
else
  ok "小上限服务已启动（上限 4096 字节）"
  SMALL_TOKEN=$(grep -o '会话令牌: .*' "${WORK}/server-small.log" | head -1 | sed 's/会话令牌: //')
  [ -n "$SMALL_TOKEN" ] || bad "小上限服务没拿到会话令牌"

  python3 -c "open('${WORK}/too-big.txt','wb').write(b'A'*8192)"
  python3 -c "open('${WORK}/just-ok.txt','wb').write(b'A'*1024)"
  code=$(curl -s -o "${WORK}/toobig.json" -w '%{http_code}' -X POST \
    --data-binary "@${WORK}/too-big.txt" \
    -H "X-Litemask-Token: ${SMALL_TOKEN}" -H "Content-Type: application/octet-stream" \
    -H "X-Filename: too-big.txt" "http://127.0.0.1:${SMALL_PORT}/api/task-scan")
  check "超限：提交时同步 400" 400 "${code}"
  check "超限的响应里没有 taskId" "" "$(task_field "${WORK}/toobig.json" taskId)"
  # 报错文案必须说的是**实际上限**（4KB），不是代码里那个 256MB 的常量——
  # 上限已经是实例参数了，文案再读常量就是在骗用户。
  check "报错文案说的是实际上限（4KB）" "true" \
    "$(grep -q '4KB' "${WORK}/toobig.json" && echo true || echo false)"
  check "报错文案没有拿 256MB 那个常量说事" "true" \
    "$(grep -q '256MB' "${WORK}/toobig.json" && echo false || echo true)"

  code=$(curl -s -o "${WORK}/justok.json" -w '%{http_code}' -X POST \
    --data-binary "@${WORK}/just-ok.txt" \
    -H "X-Litemask-Token: ${SMALL_TOKEN}" -H "Content-Type: application/octet-stream" \
    -H "X-Filename: just-ok.txt" "http://127.0.0.1:${SMALL_PORT}/api/task-scan")
  check "对照组：没超限的照样受理（202）" 202 "${code}"
fi
kill "$SRV_PID2" 2>/dev/null
wait "$SRV_PID2" 2>/dev/null
SRV_PID2=""

echo "== 9i. 崩溃残留：被强杀后原文档留在盘上，下一个实例启动时清掉 =="

# 这一条只能在**真实服务**上验：它要造出「进程被 SIGKILL 杀死」这个状态——
# close() 不执行，shutdown hook 也不跑，原文档就这么留在盘上。
# 单测里摆的是「同一种形状」（锁文件在、没人持锁），不是真的杀过进程；
# 端到端这一条才补上「真的被杀过」这一半。
#
# 判据是**盘上少了什么**，分四步，缺一步就空转：
#   ① 杀之前：会话目录里真的躺着用户原文档
#   ② 杀之后：会话目录**还在**（崩溃残留确实存在——这一步是前提，不是结论）
#   ③ 再起一个实例（同一个工作目录）：旧会话目录被清掉
#   ④ 新会话是另一个目录、旧的锁文件也没留下
CRASH_PORT=$((PORT+2))
"${PROD_CMD[@]}" -jar "$JAR" serve \
  --port "$CRASH_PORT" --workspace "${WORK}/ws-crash" --no-browser --print-token \
  > "${WORK}/server-crash.log" 2>&1 &
CRASH_PID=$!
CRASH_READY=""
for _ in $(seq 1 60); do
  if ! kill -0 "$CRASH_PID" 2>/dev/null; then break; fi
  if curl -s -o /dev/null "http://127.0.0.1:${CRASH_PORT}/api/health" 2>/dev/null; then
    CRASH_READY=yes; break
  fi
  sleep 0.25
done
if [ -z "$CRASH_READY" ]; then
  bad "崩溃测试用的服务没起来，日志：$(cat "${WORK}/server-crash.log")"
else
  ok "崩溃测试用的服务已启动"
  CRASH_TOKEN=$(grep -o '会话令牌: .*' "${WORK}/server-crash.log" | head -1 | sed 's/会话令牌: //')
  [ -n "$CRASH_TOKEN" ] || bad "崩溃测试用的服务没拿到会话令牌"

  printf '身份证号 110101199001011234\n' > "${WORK}/crash-doc.txt"
  code=$(curl -s -o "${WORK}/crash-scan.json" -w '%{http_code}' -X POST \
    --data-binary "@${WORK}/crash-doc.txt" \
    -H "X-Litemask-Token: ${CRASH_TOKEN}" -H "Content-Type: application/octet-stream" \
    -H "X-Filename: crash-doc.txt" "http://127.0.0.1:${CRASH_PORT}/api/task-scan")
  check "崩溃测试：上传被受理（202）" 202 "${code}"

  # 等任务真的跑完：原文档要落进会话目录，否则第 ② 步验的是个空目录
  CRASH_DIR=""
  for _ in $(seq 1 40); do
    CRASH_DIR=$(ls -d "${WORK}"/ws-crash/sessions/*/ 2>/dev/null | head -1)
    if [ -n "$CRASH_DIR" ] && [ "$(count_files "$CRASH_DIR")" -gt 0 ]; then break; fi
    sleep 0.25
  done
  [ -n "$CRASH_DIR" ] && ok "崩溃测试：会话目录已建立" || bad "崩溃测试：没找到会话目录"
  CRASH_N=$(count_files "$CRASH_DIR")
  if [ "${CRASH_N}" -gt 0 ]; then
    ok "崩溃测试：会话目录里确实躺着原文档（${CRASH_N} 个文件）"
  else
    bad "崩溃测试：会话目录里没有文件，后面的判据会空转"
  fi
  CRASH_SID=$(basename "${CRASH_DIR}")

  # ── 真的杀它 ──
  kill -9 "$CRASH_PID" 2>/dev/null
  wait "$CRASH_PID" 2>/dev/null
  CRASH_PID=""
  sleep 0.5

  if [ -n "$CRASH_DIR" ] && [ -d "$CRASH_DIR" ]; then
    ok "被 SIGKILL 杀掉后，会话目录仍在盘上（崩溃残留确实存在）"
  else
    bad "被 SIGKILL 杀掉后会话目录不见了：崩溃残留这个前提没成立"
  fi
  # 判「还在」而不是判「文件数与杀之前一样」：扫描任务可能还在跑，
  # 杀之前抓的那个文件数随时可能变（预览件是后落盘的），
  # 拿它做精确相等就是一条会偶发红的断言。杀掉之后没人再写盘了，
  # 这时点一次文件数才是稳定的。
  CRASH_AFTER_N=$(count_files "$CRASH_DIR")
  if [ "${CRASH_AFTER_N}" -gt 0 ]; then
    ok "崩溃残留里的原文档也还在（${CRASH_AFTER_N} 个文件，没被谁偷偷清掉）"
  else
    bad "崩溃残留里一个文件都不剩了：原文档去哪了"
  fi

  # ── 再起一个实例，用**同一个**工作目录 ──
  RESTART_PORT=$((PORT+3))
  "${PROD_CMD[@]}" -jar "$JAR" serve \
    --port "$RESTART_PORT" --workspace "${WORK}/ws-crash" --no-browser --print-token \
    > "${WORK}/server-restart.log" 2>&1 &
  RESTART_PID=$!
  RESTART_READY=""
  for _ in $(seq 1 60); do
    if ! kill -0 "$RESTART_PID" 2>/dev/null; then break; fi
    if curl -s -o /dev/null "http://127.0.0.1:${RESTART_PORT}/api/health" 2>/dev/null; then
      RESTART_READY=yes; break
    fi
    sleep 0.25
  done
  if [ -z "$RESTART_READY" ]; then
    bad "重启后的服务没起来，日志：$(cat "${WORK}/server-restart.log")"
  else
    ok "重启后的服务已启动（同一个工作目录）"
    if [ -n "$CRASH_DIR" ] && [ ! -d "$CRASH_DIR" ]; then
      ok "崩溃残留的会话目录已被下一个实例清掉"
    else
      bad "崩溃残留的会话目录还在：${CRASH_DIR}"
    fi
    NEW_DIR=$(ls -d "${WORK}"/ws-crash/sessions/*/ 2>/dev/null | head -1)
    if [ -n "$NEW_DIR" ]; then
      ok "新实例的会话目录已建好"
    else
      bad "新实例没有会话目录：清扫把不该删的也删了"
    fi
    if [ -n "$NEW_DIR" ] && [ "$NEW_DIR" != "$CRASH_DIR" ]; then
      ok "新会话与被清掉的旧会话不是同一个"
    else
      bad "新会话和旧会话撞了：${NEW_DIR}"
    fi
    # 旧会话的锁文件也不能留着：留着就会又被当成一条残留
    if [ -f "${WORK}/ws-crash/locks/${CRASH_SID}.lock" ]; then
      bad "崩溃残留的锁文件还在：下次启动会又当它是残留"
    else
      ok "崩溃残留的锁文件也被清掉了"
    fi
  fi
  if [ -n "${RESTART_PID:-}" ]; then
    kill "$RESTART_PID" 2>/dev/null
    wait "$RESTART_PID" 2>/dev/null
    RESTART_PID=""
  fi
fi

echo "== 10. 停止后工作目录整棵删除 =="
SESSION_DIR=$(ls -d "${WORK}"/ws/sessions/*/ 2>/dev/null | head -1)
[ -n "$SESSION_DIR" ] && ok "会话目录已建立 ${SESSION_DIR}" || bad "没找到会话目录"
kill "$SRV_PID" 2>/dev/null
wait "$SRV_PID" 2>/dev/null
SRV_PID=""
sleep 1
if [ -z "$SESSION_DIR" ] || [ ! -d "${SESSION_DIR}" ]; then
  ok "退出后会话目录已删除"
else
  bad "退出后会话目录仍在：${SESSION_DIR}"
fi

echo "== 11. 端口占用时明确报错，不是静默失败 =="
"${PROD_CMD[@]}" -jar "$JAR" serve \
  --port "$PORT" --workspace "${WORK}/ws2" --no-browser > "${WORK}/busy.log" 2>&1 &
BUSY_PID=$!
sleep 3
# 上一个服务已经停了，所以这里要重新占住端口再试。用 nc 起一个占位。
if command -v nc >/dev/null 2>&1; then
  nc -l 127.0.0.1 "$PORT" >/dev/null 2>&1 &
  NC_PID=$!
  sleep 1
  prod -jar "$JAR" serve --port "$PORT" --workspace "${WORK}/ws3" --no-browser \
    > "${WORK}/busy2.log" 2>&1
  rc=$?
  kill "$NC_PID" 2>/dev/null
  if [ "$rc" -ne 0 ] && grep -q '已被占用' "${WORK}/busy2.log"; then
    ok "端口占用时报错并给出换端口建议（退出码 ${rc}）"
  else
    bad "端口占用没有正确报错（退出码 ${rc}）"; cat "${WORK}/busy2.log"
  fi
else
  echo "   （无 nc，跳过端口占用这项）"
fi
kill "$BUSY_PID" 2>/dev/null; wait "$BUSY_PID" 2>/dev/null

echo
echo "=== e2e serve: pass=${pass} fail=${fail} ==="
[ "$fail" -eq 0 ] || exit 1
