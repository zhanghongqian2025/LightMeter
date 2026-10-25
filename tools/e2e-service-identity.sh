#!/usr/bin/env bash
# 探活身份判据（块 34）：端到端。
#
# 【这一条验的是什么】
#   「autostart status 说的『服务在线』，到底是不是指**本工具的服务**在线」。
#   块 24 / 27 / 28 登记过一个老缺口（§27.12 / §28.13）：
#   `/api/health` **不需要令牌** → 原来的判据是「这个端口回 200 就算在线」。
#   → 端口被不相干的服务占着时，它**照样说在线**。
#   而 status 恰恰是用户验收「重启后服务自动起来」的那条命令：
#   它恒真 = 那条验收永远绿灯。
#
# 【为什么必须做成「真服务 vs 假服务」两场对照】
#   只验「真服务 → 在线」证明不了任何事 ——
#   **恒真的判据（只看 200）在这一场里也是绿的**。
#   真正有判别力的是第二场：端口上是个**不相干的服务** → 不许说在线。
#   → 这一条的价值全在第二场。
#
# 【判据独立于被测实现】
#   期望的档位（ONLINE / NOT_OURS / DOWN）是**本文件里的字面量**，
#   判据**不复用**产品的枚举、不复用产品的任何代码。
#   探活本身用产品的实现（那正是被测对象），
#   但「它应该判成什么」由本文件独立写死。
#
# 【退出码】
#   0  = 全部跑完且通过
#   1  = 有断言失败
#   77 = 环境不具备（不是通过）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:${PATH}"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
JAVAC="${LITEMASK_RUN_JAVAC:-$JAVA_HOME/bin/javac}"
MVN="$MVN_BIN -B -o -Dstyle.color=never"

. "$ROOT/tools/shell-lib.sh"

WORK="$(mktemp -d)"
JAR="$ROOT/target/desens-core.jar"
SRV_PID=""
FAKE_PID=""
pass=0; fail=0

ok()   { echo "PASS $1"; pass=$((pass+1)); }
bad()  { echo "FAIL $1"; fail=$((fail+1)); }
check(){ # $1=名称 $2=期望 $3=实际
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 $2，实际 $3）"; fi
}

cleanup() {
  [ -n "${FAKE_PID:-}" ] && kill "$FAKE_PID" 2>/dev/null
  [ -n "${SRV_PID:-}" ] && kill "$SRV_PID" 2>/dev/null
  wait "${SRV_PID:-}" 2>/dev/null
  wait "${FAKE_PID:-}" 2>/dev/null
  rm_rf "${WORK:-}"
}
trap cleanup EXIT

# 端口要真的空着，否则「服务起不来」会分不清是端口问题还是别的。
port_free() {
  python3 -c '
import socket, sys
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(1)
finally:
    s.close()
' "$1"
}

# 探一次并把三行结果拆成 shell 变量。
# 【为什么拆而不是让驱动直接判】
#   判据要写在**本文件**里（独立于被测实现）。
#   驱动只负责「跑探活、打印结论」。
STATE=""; ONLINE=""; DETAIL=""
do_probe() { # $1=端口
  local out
  out="$("$RUN_JAVA" -cp "$ROOT/target/classes:$WORK/classes" \
        E2eServiceIdentity probe "$1" 2>&1)"
  STATE="$(printf '%s\n' "$out" | grep '^STATE=' | head -1 | sed 's/^STATE=//')"
  ONLINE="$(printf '%s\n' "$out" | grep '^ONLINE=' | head -1 | sed 's/^ONLINE=//')"
  DETAIL="$(printf '%s\n' "$out" | grep '^DETAIL=' | head -1 | sed 's/^DETAIL=//')"
}

echo "== 0. 前置检查 =="
command -v java >/dev/null 2>&1 || { echo "SKIP 找不到 java。"; exit 77; }
command -v javac >/dev/null 2>&1 || { echo "SKIP 找不到 javac。"; exit 77; }
command -v curl >/dev/null 2>&1 || { echo "SKIP 找不到 curl。"; exit 77; }

echo ""
echo "== 1. 构建 fat jar + 编译驱动 =="
( cd "$ROOT" && $MVN -q -f "$ROOT/pom.xml" -DskipTests package ) > "$WORK/build.log" 2>&1
check "构建 fat jar" "0" "$?"
[ -f "$JAR" ] && ok "fat jar 在位" || { bad "fat jar 不在: $JAR"; tail -20 "$WORK/build.log"; exit 1; }

# 探活用的是产品**源码树编译出来的** class（target/classes），
# 不是 jar 里的 —— 因为驱动要与产品类在同一个 classpath 上跑。
# 【为什么不从 jar 里取产品类】
#   jar 是 shade 后的产物，类路径形态不同（BOOT-INF/classes vs 顶层）。
#   从 jar 里取会要求驱动适配那种布局，而这里验的是**探活逻辑**，
#   不是打包布局（打包布局由 e2e-serve 那条守）。
#   → 顺带：探活这一条与打包无关，用 target/classes 是**更准**的选择。
if [ ! -d "$ROOT/target/classes/com/litemask/cli" ]; then
  bad "target/classes 里没有产品 class（先构建）"
  exit 1
fi

mkdir -p "$WORK/classes"
# 驱动要 import 产品的 ServiceProbe → 编译时**必须**给 classpath。
# 【为什么驱动可以 import 产品代码，而断言不 import】
#   驱动是「**跑**被测对象」的驱动器：它 import 产品类是为了**调用**它，
#   这正是被测行为本身。
#   但「它应该判成什么」由 shell 侧的字面量决定 → 判据与实现分开。
if ! "$JAVAC" -encoding UTF-8 -cp "$ROOT/target/classes" \
      -d "$WORK/classes" "$ROOT/tools/E2eServiceIdentity.java"; then
  bad "端到端驱动编译不过"
  exit 1
fi
ok "端到端驱动编译通过"

PORT="${E2E_PORT:-18977}"
while ! port_free "$PORT"; do
  PORT=$((PORT + 1))
  [ "$PORT" -gt 19020 ] && { bad "找不到空着的端口"; exit 1; }
done
ok "端口 ${PORT} 空着"

echo ""
echo "== 2. 第一场：真服务 → 判『在线』 =="
# 【为什么这一场不是重点，但必须有】
#   恒真的判据在这一场里**也是绿的** → 它证明不了判别力。
#   它证明的是**另一件事**：判据没有「严到把真服务也判成不是本工具」。
#   （这是个真实的风险：判据写太严会把好服务判坏，而这种红是「看起来红」，
#     很容易被误当成「测试发现问题」。）
"$RUN_JAVA" -jar "$JAR" serve --port "$PORT" --workspace "$WORK/ws" \
  --no-browser > "$WORK/server.log" 2>&1 &
SRV_PID=$!

READY=""
for _ in $(seq 1 80); do
  if ! kill -0 "$SRV_PID" 2>/dev/null; then break; fi
  if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:${PORT}/api/health" 2>/dev/null; then
    READY=yes; break
  fi
  sleep 0.25
done
if [ -z "$READY" ]; then
  bad "真服务没起来，日志如下："; cat "$WORK/server.log"; exit 1
fi
ok "真服务在监听 ${PORT}"

do_probe "$PORT"
check "真服务被判为在线" "ONLINE" "$STATE"
check "真服务的 online 标志为真" "true" "$ONLINE"

# 服务名与协议版本要真的报出来（否则上面那条「在线」是空转的）。
HEALTH="$(curl -s --max-time 3 "http://127.0.0.1:${PORT}/api/health")"
case "$HEALTH" in
  *'"service"'*) ok "健康检查报出了服务名字段" ;;
  *) bad "健康检查没报服务名字段：$HEALTH" ;;
esac
case "$HEALTH" in
  *'"protocol"'*) ok "健康检查报出了协议版本字段" ;;
  *) bad "健康检查没报协议版本字段：$HEALTH" ;;
esac

# 挑战回显：发一个形态合法的挑战，服务端要原样回显。
CH="AbCdEfGhIjKlMnOpQrSt"
ECHO="$(curl -s --max-time 3 -H "X-Litemask-Challenge: ${CH}" \
        "http://127.0.0.1:${PORT}/api/health")"
case "$ECHO" in
  *"\"${CH}\""*) ok "服务端原样回显了挑战" ;;
  *) bad "服务端没回显挑战：$ECHO" ;;
esac

# 不带挑战也要回 200 且报身份（公开端点这条路不许被堵死）。
NOC="$(curl -s -o "$WORK/noc.body" -w '%{http_code}' --max-time 3 \
       "http://127.0.0.1:${PORT}/api/health")"
check "不带挑战时健康检查仍回 200" "200" "$NOC"
case "$(cat "$WORK/noc.body")" in
  *'"service"'*) ok "不带挑战时仍报出服务名（手工 curl 一下就能查）" ;;
  *) bad "不带挑战时没报服务名：$(cat "$WORK/noc.body")" ;;
esac

echo ""
echo "== 3. 第二场：端口上是个不相干的服务 → 判『不是本工具』 =="
# 【这一场是这一条的判别力所在】
#   恒真的判据（只看 HTTP 200）在这里会**绿** → 它必须红。
#
# 【四种形态，每个只被一道核对挡住】
#   这一点是**必须的**，不是讲究：
#   探活的三道核对是**顺序判**的（第一道不匹配就返回）。
#   若某个形态同时缺好几样东西（例如既不声明服务名、也不回显挑战），
#   那么「拆掉服务名核对」这个变异**照样红**（红在别的形态上），
#   而「服务名核对」这一道就**永远没被单独验过**。
#   → 每个形态必须**只**缺一样东西，且那一项**只能被它要打的那一道核对挡住**。

# ---- 形态一：什么都不声明（最常见的现实场景） ----
FAKE_PORT=$((PORT + 1))
while ! port_free "$FAKE_PORT"; do FAKE_PORT=$((FAKE_PORT + 1)); done
"$RUN_JAVA" -cp "$WORK/classes:$ROOT/target/classes" \
  E2eServiceIdentity fake-plain "$FAKE_PORT" > "$WORK/fake-plain.log" 2>&1 &
FAKE_PID=$!
for _ in $(seq 1 40); do
  grep -q "FAKE-READY" "$WORK/fake-plain.log" 2>/dev/null && break
  sleep 0.25
done
if ! grep -q "FAKE-READY" "$WORK/fake-plain.log" 2>/dev/null; then
  bad "假服务（plain）没起来"; cat "$WORK/fake-plain.log"; exit 1
fi
do_probe "$FAKE_PORT"
check "不相干的服务被判为『不是本工具』" "NOT_OURS" "$STATE"
check "不相干的服务的 online 标志为假" "false" "$ONLINE"
kill "$FAKE_PID" 2>/dev/null; wait "$FAKE_PID" 2>/dev/null; FAKE_PID=""

# ---- 形态二：服务名不对（版本对、挑战也对）→ 专打「服务名核对」 ----
FAKE_PORT=$((PORT + 2))
while ! port_free "$FAKE_PORT"; do FAKE_PORT=$((FAKE_PORT + 1)); done
"$RUN_JAVA" -cp "$WORK/classes:$ROOT/target/classes" \
  E2eServiceIdentity fake-wrong-name "$FAKE_PORT" > "$WORK/fake-wrong-name.log" 2>&1 &
FAKE_PID=$!
for _ in $(seq 1 40); do
  grep -q "FAKE-READY" "$WORK/fake-wrong-name.log" 2>/dev/null && break
  sleep 0.25
done
if ! grep -q "FAKE-READY" "$WORK/fake-wrong-name.log" 2>/dev/null; then
  bad "假服务（wrong-name）没起来"; cat "$WORK/fake-wrong-name.log"; exit 1
fi
do_probe "$FAKE_PORT"
check "自称别的服务名 → 判『不是本工具』" "NOT_OURS" "$STATE"
kill "$FAKE_PID" 2>/dev/null; wait "$FAKE_PID" 2>/dev/null; FAKE_PID=""

# ---- 形态三：声明了身份与版本，但**不回显挑战** → 专打「挑战核对」 ----
FAKE_PORT=$((PORT + 3))
while ! port_free "$FAKE_PORT"; do FAKE_PORT=$((FAKE_PORT + 1)); done
"$RUN_JAVA" -cp "$WORK/classes:$ROOT/target/classes" \
  E2eServiceIdentity fake-declared "$FAKE_PORT" > "$WORK/fake-declared.log" 2>&1 &
FAKE_PID=$!
for _ in $(seq 1 40); do
  grep -q "FAKE-READY" "$WORK/fake-declared.log" 2>/dev/null && break
  sleep 0.25
done
if ! grep -q "FAKE-READY" "$WORK/fake-declared.log" 2>/dev/null; then
  bad "假服务（declared）没起来"; cat "$WORK/fake-declared.log"; exit 1
fi
do_probe "$FAKE_PORT"
check "只声明身份但不回显挑战 → 判『不是本工具』" "NOT_OURS" "$STATE"
kill "$FAKE_PID" 2>/dev/null; wait "$FAKE_PID" 2>/dev/null; FAKE_PID=""

# ---- 形态四：服务名对，但协议版本不对（挑战也对）→ 专打「协议版本核对」 ----
FAKE_PORT=$((PORT + 4))
while ! port_free "$FAKE_PORT"; do FAKE_PORT=$((FAKE_PORT + 1)); done
"$RUN_JAVA" -cp "$WORK/classes:$ROOT/target/classes" \
  E2eServiceIdentity fake-wrong-protocol "$FAKE_PORT" > "$WORK/fake-wrong.log" 2>&1 &
FAKE_PID=$!
for _ in $(seq 1 40); do
  grep -q "FAKE-READY" "$WORK/fake-wrong.log" 2>/dev/null && break
  sleep 0.25
done
if ! grep -q "FAKE-READY" "$WORK/fake-wrong.log" 2>/dev/null; then
  bad "假服务（wrong-protocol）没起来"; cat "$WORK/fake-wrong.log"; exit 1
fi
do_probe "$FAKE_PORT"
check "协议版本不对 → 判『不是本工具』" "NOT_OURS" "$STATE"
kill "$FAKE_PID" 2>/dev/null; wait "$FAKE_PID" 2>/dev/null; FAKE_PID=""

echo ""
echo "== 4. 第三场：端口上没人听 → 判『没在监听』 =="
# 这一场把「不是本工具」与「压根没人」分开：
#   两者要用户做的事完全不同（前者赶走占端口的人，后者去装自启）。
DEAD_PORT=$((PORT + 40))
while ! port_free "$DEAD_PORT"; do DEAD_PORT=$((DEAD_PORT + 1)); done
do_probe "$DEAD_PORT"
check "没人监听时判『没在监听』" "DOWN" "$STATE"
check "没人监听时 online 标志为假" "false" "$ONLINE"

echo ""
echo "== 5. 真服务停下来之后 → 档位要从『在线』变成『没在监听』 =="
# 【为什么要有这一场】
#   上面四场验的是「档位分得开」。
#   这一场验的是**同一个端口**在服务起落两态之间切换时档位跟着变 ——
#   如果判据把结果缓存住了，第一场之后档位就不再随现实变。
kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; SRV_PID=""
sleep 1
do_probe "$PORT"
check "服务停下后判『没在监听』" "DOWN" "$STATE"

echo ""
echo "=== e2e-service-identity: pass=${pass} fail=${fail} ==="
[ "$fail" -eq 0 ] || exit 1
echo "端到端：通过"
exit 0
