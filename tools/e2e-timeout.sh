#!/usr/bin/env bash
# 块 9 端到端：走打包后的 fat jar，验「长任务超时」在**真实服务**上成立。
#
# 【为什么要有这个脚本】
#   单测 TaskTimeoutTest 验的是「TaskRunner 这个类对不对」；
#   这里验的是「打好的 jar、真的起起来的服务、真的从外部打进去」——
#   包括：时限参数真的被 CLI 接受并生效、服务真的把任务掐停、
#   快照里的终态真的是 timed_out、/api/health 真的报出了时限。
#   打包这一步会换掉 mainClass 与类加载路径，只测 target/classes 覆盖不到它。
#
# 【为什么时限要能在 CLI 上调】
#   默认时限是 30 分钟。e2e 不能干等 30 分钟——那种 e2e 等同于没写。
#   所以 serve 要有 --task-timeout；这条脚本用 1 秒。
#
# 【怎么保证「超时真的发生」而不是「任务本来就快」】
#   传一份**很大**的 docx（几千段）。大到 1 秒跑不完，
#   于是「超时」是确定的事实，不是和机器快慢赛跑。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 自己 cd 过去：下面要在这个目录里 mvn package，靠调用者的 cwd 是个隐患
# （从别的目录调用时会打到错误的工程上，或者根本没这个 pom）。
cd "$ROOT" || exit 1
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="${JAVA_HOME}/bin:${PATH}"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
MVN="$MVN_BIN -B -o -Dstyle.color=never"

PORT="${E2E_TIMEOUT_PORT:-18935}"
TIMEOUT_MS="${E2E_TIMEOUT_MS:-1000}"
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
contains(){ # $1=名称 $2=子串 $3=文件
  if grep -q -- "$2" "$3"; then ok "$1"; else bad "$1（文件里没有：$2）"; fi
}
not_contains(){ # $1=名称 $2=子串 $3=文件
  if grep -q -- "$2" "$3"; then bad "$1（文件里不该有：$2）"; else ok "$1"; fi
}

. "$ROOT/tools/e2e-common.sh"
e2e_form_banner

cleanup() {
  [ -n "${SRV_PID:-}" ] && kill "$SRV_PID" 2>/dev/null
  wait "$SRV_PID" 2>/dev/null
  # mktemp -d 是绝对路径，rm 不会被相对路径的网关规则拦
  [ -n "${WORK:-}" ] && rm_rf "${WORK}"
}
trap cleanup EXIT

echo "== 构建 fat jar =="
$MVN -f "$ROOT/pom.xml" package -DskipTests -q 2>&1 | tail -3
[ -f "$JAR" ] || { echo "X 没有产出 ${JAR}，实验无效"; exit 1; }
echo "   $(wc -c < "$JAR") 字节"

echo "== 启动本地服务（端口 ${PORT}，任务时限 ${TIMEOUT_MS} 毫秒）=="
"${PROD_CMD[@]}" -jar "$JAR" serve \
  --port "$PORT" --workspace "${WORK}/ws" --no-browser --print-token \
  --task-timeout "$TIMEOUT_MS" \
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
TOKEN=$(grep -o '会话令牌: .*' "${WORK}/server.log" | head -1 | sed 's/会话令牌: //')
[ -n "$TOKEN" ] || { echo "X 没拿到会话令牌"; exit 1; }

# ---------------------------------------------------------------------------
echo "== 1. 时限参数真的生效 =="
curl -s -o "${WORK}/h.json" "http://127.0.0.1:${PORT}/api/health"
contains "health 报出了任务时限" "\"taskTimeoutMs\":${TIMEOUT_MS}" "${WORK}/h.json"
contains "health 报出了超时计数" "\"timedOutTasks\":" "${WORK}/h.json"
contains "启动横幅里打了时限" "任务时限" "${WORK}/server.log"

echo "== 2. 造一个跑不完的任务，让它真的超时 =="
# 大文本文件：**不放**任何能命中的标识符。
# 放了会生成十几万条替换明细，既慢又占内存，
# 而这里要的只是「让扫描跑得足够久，久到超时能落在管线中途」。
# 做法与 e2e-serve.sh 造「慢任务」那一段保持一致。
python3 - "${WORK}/big.txt" <<'PY'
import sys
with open(sys.argv[1], "w", encoding="utf-8") as f:
    for i in range(400000):
        f.write("第%d行 这是一段用来把扫描拖慢的普通文字，里面没有任何可命中的标识符。\n" % i)
PY
[ -f "${WORK}/big.txt" ] || { echo "X 样本没造出来"; exit 1; }

# 提交**两个**大任务：工作线程只有 2 个，两个都占住之后，
# 后面提交的任务必然在队列里等——而时限从「开跑」算，
# 排队等待不算在时限内，所以它轮到开跑时才开始计时。
# 只提交一个的话，它可能秒完成，**超时这件事就没发生**，
# 而脚本会误以为「超时了」——那是「测了但没真测」的方向。
for n in 1 2; do
  code=$(curl -s -o "${WORK}/submit${n}.json" -w '%{http_code}' -X POST \
    -H "Host: 127.0.0.1:${PORT}" -H "X-Litemask-Token: ${TOKEN}" \
    -H "Content-Type: application/octet-stream" \
    -H "X-Filename: big.txt" \
    --data-binary "@${WORK}/big.txt" \
    "http://127.0.0.1:${PORT}/api/task-scan")
  check "提交第 ${n} 个大任务返回 202" 202 "${code}"
done

# 第三个：它会排队，等前面两个超时腾出位置后才开跑
code=$(curl -s -o "${WORK}/submit3.json" -w '%{http_code}' -X POST \
  -H "Host: 127.0.0.1:${PORT}" -H "X-Litemask-Token: ${TOKEN}" \
  -H "Content-Type: application/octet-stream" \
  -H "X-Filename: big.txt" \
  --data-binary "@${WORK}/big.txt" \
  "http://127.0.0.1:${PORT}/api/task-scan")
check "提交第三个大任务返回 202" 202 "${code}"
TASK_ID=$(grep -o '"taskId":"[^"]*"' "${WORK}/submit3.json" | head -1 | sed 's/.*"taskId":"//; s/"$//')
[ -n "$TASK_ID" ] || { echo "X 没拿到 taskId：$(cat "${WORK}/submit3.json")"; exit 1; }
ok "拿到了 taskId"

echo "== 3. 等它们都到终态 =="
# **三个都要等到**：只等第三个的话，前两个可能还在跑，
# 那么下一步「工作区零残留」的判据就不成立——
# 它们随后才清理，检查却发生在之前，那条断言就变成「运气好」。
wait_terminal() { # $1=taskId $2=输出文件
  local tid="$1" out="$2" st=""
  for _ in $(seq 1 240); do
    curl -s -o "$out" \
      -H "Host: 127.0.0.1:${PORT}" -H "X-Litemask-Token: ${TOKEN}" \
      "http://127.0.0.1:${PORT}/api/task?taskId=${tid}"
    st=$(grep -o '"status":"[^"]*"' "$out" | head -1 | sed 's/.*"status":"//; s/"$//')
    [ "$st" != "running" ] && { echo "$st"; return 0; }
    sleep 0.5
  done
  echo "$st"
}
T1=$(grep -o '"taskId":"[^"]*"' "${WORK}/submit1.json" | head -1 | sed 's/.*"taskId":"//; s/"$//')
T2=$(grep -o '"taskId":"[^"]*"' "${WORK}/submit2.json" | head -1 | sed 's/.*"taskId":"//; s/"$//')
S1=$(wait_terminal "$T1" "${WORK}/task1.json")
S2=$(wait_terminal "$T2" "${WORK}/task2.json")
STATUS=$(wait_terminal "$TASK_ID" "${WORK}/task.json")

check "第一个任务终态是 timed_out" "timed_out" "${S1}"
check "第二个任务终态是 timed_out" "timed_out" "${S2}"
check "第三个任务终态是 timed_out" "timed_out" "${STATUS}"
contains "快照带 timedOut 标记" '"timedOut":true' "${WORK}/task.json"
contains "快照说了为什么停" "超过时限" "${WORK}/task.json"
not_contains "快照里没有作业号（没产出）" '"jobId"' "${WORK}/task.json"

echo "== 4. 超时后磁盘上什么都没留下 =="
# 关键判据：状态只是说法，文件才是后果。
LEFT=$(find "${WORK}/ws" -type f ! -name '*.lock' 2>/dev/null | wc -l | tr -d ' ')
check "工作区里没有残留文件" 0 "${LEFT}"

echo "== 5. 超时次数被如实记下来 =="
curl -s -o "${WORK}/h2.json" "http://127.0.0.1:${PORT}/api/health"
# 精确等于 3：上面已经逐个断言过三个任务的终态都是 timed_out，
# 而 timedOutTasks 是**累计**计数（每个任务只计一次，不是每次扫到都加）。
# 所以「三个任务都超时」对应「计数 = 3」——写成 1 会让这条断言永远红，
# 写成 >=1 又会漏掉「计数算错」这种问题（比如同一个任务被重复计数成 5）。
contains "health 里超时计数 = 3" '"timedOutTasks":3' "${WORK}/h2.json"

echo "== 6. 服务还活着（超时不能让服务瘫掉）=="
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/api/health")
check "服务仍然答得上健康检查" 200 "${code}"

echo "== 7. 参数校验 =="
"${PROD_CMD[@]}" -jar "$JAR" serve --port "$((PORT+1))" --task-timeout 负数 \
  > "${WORK}/bad.log" 2>&1
rc=$?
check "负的时限被拒（退出码 2）" 2 "${rc}"
contains "报错说清了" "--task-timeout" "${WORK}/bad.log"

"${PROD_CMD[@]}" -jar "$JAR" serve --port "$((PORT+1))" --task-timeout \
  > "${WORK}/bad2.log" 2>&1
rc=$?
check "缺参数被拒（退出码 2）" 2 "${rc}"

echo "== 8. 0 表示不设时限 =="
"${PROD_CMD[@]}" -jar "$JAR" serve --port "$((PORT+1))" --workspace "${WORK}/ws2" \
  --no-browser --task-timeout 0 > "${WORK}/notimeout.log" 2>&1 &
SRV2_PID=$!
READY2=""
for _ in $(seq 1 40); do
  if curl -s -o /dev/null "http://127.0.0.1:$((PORT+1))/api/health" 2>/dev/null; then
    READY2=yes; break
  fi
  sleep 0.25
done
if [ -n "$READY2" ]; then
  ok "不设时限的服务起得来"
  curl -s -o "${WORK}/h3.json" "http://127.0.0.1:$((PORT+1))/api/health"
  contains "health 报时限为 0" '"taskTimeoutMs":0' "${WORK}/h3.json"
else
  bad "不设时限的服务没起来"
fi
kill "$SRV2_PID" 2>/dev/null
wait "$SRV2_PID" 2>/dev/null

echo
echo "== 结果：pass=${pass} fail=${fail} =="
[ "$fail" -eq 0 ] || exit 1
