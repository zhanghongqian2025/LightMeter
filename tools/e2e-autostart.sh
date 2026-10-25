#!/usr/bin/env bash
# macOS 开机自启（LaunchAgent）：端到端。
#
# 【这一块为什么单独立一个 e2e】
#   开机自启的失败几乎全是**静默**的：plist 字段写错、launchd 不装载、
#   服务起来就退出、KeepAlive 语义写反导致无限重试——
#   现场表现都是「命令说成功了，但机器上没有那个服务」。
#   单测能逐字段验 plist，验不了「launchd 到底认不认、起不起来、崩了会不会自愈」。
#   → 这一条必须**真装一次、真起一次、真杀一次**。
#
# 【这一条测的是什么、不是什么】
#   测的是**自启这条链路**：plist → launchctl → launchd → 进程 → 端口 → HTTP。
#   为了不碰这台机器真的 ~/Library/LaunchAgents，
#   这一条用**独立的 user.home**（plist 落在临时目录里）；
#   launchd 那边是真的——实测 `launchctl bootstrap gui/<uid> <任意路径>.plist`
#   接受不在 ~/Library/LaunchAgents 里的 plist（launchd 只按给定路径读文件）。
#   → 「plist 该放在 ~/Library/LaunchAgents 里」由单测的路径判据守；
#     这一条守的是「放对了 launchd 认不认、起不起来、崩了会不会自愈、
#     端口被占会不会反复重试」。
#
# 【判据独立于被测实现】
#   服务状态一律用 launchctl / curl / kill / plutil 读，
#   **不复用** Java 侧的任何代码；plist 的合法性由系统自带的 plutil 判。
#
# 【退出码】
#   0  = 全部跑完且通过
#   1  = 有断言失败
#   77 = 环境不具备（不是 macOS / 没有 launchctl / gui 会话不可用）——**不是通过**
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
MVN="$MVN_BIN -B -o -Dstyle.color=never"
JAR="$ROOT/target/desens-core.jar"

. "$ROOT/tools/e2e-common.sh"

WORK="$(mktemp -d)"
UHOME="$WORK/用户主目录"
UTMP="$WORK/临时目录"
LABEL="com.litemask.lightmeter"
UID_NUM="$(id -u)"
PLIST_PATH="$UHOME/Library/LaunchAgents/${LABEL}.plist"
APP_DIR="$WORK/LightMeter.app"
PORT=18099
OCCUPIER_PID=""

pass=0; fail=0
ok()   { echo "PASS $1"; pass=$((pass+1)); }
bad()  { echo "FAIL $1"; fail=$((fail+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 $2，实际 $3）"; fi; }
contains(){ # $1=名称 $2=文件 $3=应含的串
  if [ ! -f "$2" ]; then bad "$1（要查的文件不存在: $2）"; return; fi
  if grep -qF -- "$3" "$2"; then ok "$1"; else
    bad "$1（文件里找不到「$3」）"; sed -n '1,20p' "$2" | sed 's/^/    | /'
  fi
}

# 收尾：把 launchd 里可能残留的服务卸掉、把 plist 删掉。
# 【为什么必须有】这一条是**真的**往 launchd 里装东西的；
# 中途失败直接退出的话，机器上会留一个常驻服务 + 一个 plist，
# 下次开机它还会自己起来 —— 那是测试在用户机器上留下的东西，不能这样。
cleanup() {
  launchctl bootout "gui/${UID_NUM}/${LABEL}" >/dev/null 2>&1
  [ -n "$OCCUPIER_PID" ] && kill "$OCCUPIER_PID" >/dev/null 2>&1
  rm_rf "$WORK"
}
trap cleanup EXIT

# launchctl print 的关键字段。
svc_field() { # $1=字段名
  launchctl print "gui/${UID_NUM}/${LABEL}" 2>/dev/null | grep -E "^[[:space:]]*$1 = " | head -1 | sed -E "s/.*= //"
}
svc_pid()   { svc_field "pid"; }
svc_runs()  { svc_field "runs"; }
svc_state() { svc_field "state"; }
svc_loaded() {
  launchctl print "gui/${UID_NUM}/${LABEL}" >/dev/null 2>&1 && echo yes || echo no
}

echo "== 0. 前置检查 =="
if [ "$(uname -s)" != "Darwin" ]; then
  echo "SKIP 这不是 macOS，开机自启只实现了 macOS（LaunchAgent）。"
  exit 77
fi
command -v launchctl >/dev/null 2>&1 || { echo "SKIP 没有 launchctl。"; exit 77; }
command -v plutil >/dev/null 2>&1 || { echo "SKIP 没有 plutil。"; exit 77; }
if ! launchctl print "gui/${UID_NUM}" >/dev/null 2>&1; then
  echo "SKIP 当前用户（uid=${UID_NUM}）的 gui 会话不可用 —— 这一条要真装 launchd 服务。"
  exit 77
fi
echo "   uid=${UID_NUM}；gui 会话可用；label=${LABEL}"

echo ""
echo "== 1. 构建 fat jar =="
( cd "$ROOT" && $MVN -q -f "$ROOT/pom.xml" -DskipTests package ) > "$WORK/build.log" 2>&1
check "构建 fat jar" "0" "$?"
[ -f "$JAR" ] && ok "fat jar 在位" || { bad "fat jar 不在: $JAR"; exit 1; }

mkdir -p "$UHOME" "$UTMP" "$APP_DIR/Contents/MacOS"

echo ""
echo "== 2. 把 launchd 要读的东西搬出 TCC 受保护目录 =="
# 【TCC 隔离 —— 2026-10-21 实测事故，必读】
# macOS 的 TCC 会保护 ~/Desktop、~/Documents、~/Downloads（~ 根、/tmp、$TMPDIR 不保护）。
# launchd 拉起的进程去读这些目录时，**失败形态分两种**：
#   - 平台二进制（/bin/cat）→ 快速失败：Operation not permitted
#   - 第三方二进制（本项目的 JVM）→ **永久挂起**。tccd 日志原话：
#     "could not resolve a bundle identifier for the requesting process; not prompting"
#     —— launchd 拉起的第三方二进制没有 bundle id，授权窗弹不出来，请求就悬着。
# 挂起时的现场极像「服务起来了但端口不通」：launchctl 报 state=running、有 pid、
# runs 在涨，可端口就是没反应；sample 出来主线程停在 dyld 的 open() 里，RSS 只有 160K。
#
# 本项目的代码库在 ~/Documents 下 → 直接拿它当被测对象，**e2e 必红，且红得莫名其妙**。
# 所以：**launchd 要碰的每一个文件都必须搬到 $TMPDIR 下面**。
#
# 【真实产品不受影响，理由逐条】
#   - 程序本体装在 /Applications        → 不受保护
#   - 模型包在 ~/.litemask/models/ner   → ~ 根下，不受保护
#   - 工作区在 $TMPDIR/litemask-workspace → 不受保护
#   - 浏览器上传的是**字节**不是路径     → 服务不需要去读 ~/Documents 里的原文件
# 这段不是「免责」，而是把边界写清楚：哪天改成让服务端按路径读原文件，就必须重看这里。
#
# 【这段修复的自证 —— 2026-10-21 实测】
# 光有「守卫生效」不够：那可能只是守卫在挡，判据本身未必咬得住。
# 所以另做了一个**骗过守卫**的变异体：保留克隆（TCC 隔离看着一切正常），
# 只让**真正被 launchd 执行**的 launcher 指回 ~/Documents 下的 JVM 与 jar。
# → 变异体跑出 **pass=40 fail=5，rc=1**，红点恰好是：
#     端口被占时服务没有重试（runs=1）／日志里说清了端口被占／
#     端口腾出来后服务自动就位（期望 200，实际 000）
#   即：**端口判据确实咬得住这个缺陷**，绿不是守卫装饰出来的。
# （顺带记一条同源的坑：探针也得自证。若用 ~/Documents 下的 JDK 测「bootout 多久生效」，
#   那个 JVM 根本没起来、是被 SIGTERM 秒杀的，会假出「+0s 就消失」；
#   真跑起来的服务要 1.1 秒。→ 探针必须先自证被测对象真的处于目标状态。）
LAUNCH_DIR="$WORK/运行时"
LAUNCH_JAVA="$LAUNCH_DIR/bin/java"
LAUNCH_JAR="$WORK/app.jar"
# cp -c 是 APFS 克隆（copy-on-write）：336MB 的 JDK 克隆只要 0.06 秒，且几乎不额外占盘。
# 跨卷时 cp -c 会失败，所以留 cp -R 兜底——两条都不成就是环境不具备，不能糊过去。
if cp -Rc "$JAVA_HOME" "$LAUNCH_DIR" 2>/dev/null || cp -R "$JAVA_HOME" "$LAUNCH_DIR" 2>/dev/null; then
  ok "运行时已克隆到 $LAUNCH_DIR"
else
  bad "运行时克隆失败：$JAVA_HOME → $LAUNCH_DIR"; exit 1
fi
cp -f "$JAR" "$LAUNCH_JAR" 2>/dev/null || cp -f "$JAR" "$LAUNCH_JAR"
[ -x "$LAUNCH_JAVA" ] && [ -f "$LAUNCH_JAR" ] \
  && ok "被测运行时与 jar 都在位（且都不在 TCC 受保护目录里）" \
  || { bad "被测运行时或 jar 不在位"; exit 1; }

# 守卫：被 launchd 拉起的东西一旦指回受保护目录，就又变成「静默挂死」。
# 这条要当场报出来，而不是等后面 30 秒轮询后报一句「端口不通」让人摸不着头脑。
TCC_HIT=0
for p in "$LAUNCH_JAVA" "$LAUNCH_JAR" "$APP_DIR/Contents/MacOS/LightMeter" "$UHOME" "$UTMP"; do
  case "$p" in
    */Desktop/*|*/Documents/*|*/Downloads/*) TCC_HIT=1; echo "    命中受保护路径: $p" ;;
  esac
done
if [ "$TCC_HIT" -eq 0 ]; then
  ok "被 launchd 拉起的路径都不在 TCC 受保护目录里"
else
  bad "有路径落在 TCC 受保护目录里（~/Desktop、~/Documents、~/Downloads）——launchd 拉起后会静默挂死"
  exit 1
fi

echo ""
echo "== 3. 造一个「假 .app」（真的 .app 由打包那条链验） =="
# 这一条要验的是**自启链路**（plist → launchd → 进程 → 端口），
# 不是打包。所以这里用一个小启动脚本占住 .app 里那个可执行文件的位置：
# 路径形态（Contents/MacOS/LightMeter）与真 .app 完全一致，
# 里面跑的是**真的 fat jar**（服务是真服务，HTTP 是真 HTTP）。
#
# 【为什么用克隆出来的运行时和 jar，而不是 $RUN_JAVA / $JAR】
# 因为 launchd 拉起的进程没有 bundle id，TCC 弹不出授权窗，
# 去读 ~/Documents 下的文件会**永久挂起**（见上面那段实测）。
# $WORK 在 $TMPDIR 下 → 不受保护 → 服务能真起来。
printf '#!/bin/sh\nexec "%s" -Duser.home="%s" -Djava.io.tmpdir="%s" -jar "%s" "$@"\n' \
  "$LAUNCH_JAVA" "$UHOME" "$UTMP" "$LAUNCH_JAR" > "$APP_DIR/Contents/MacOS/LightMeter"
chmod +x "$APP_DIR/Contents/MacOS/LightMeter"
[ -x "$APP_DIR/Contents/MacOS/LightMeter" ] && ok "假 .app 的可执行文件就位" \
  || { bad "假 .app 的可执行文件不可执行"; exit 1; }

# 产品调用统一用**隔离的 user.home**：plist 落在临时目录里，
# 碰不到这台机器真的 ~/Library/LaunchAgents。
#
# 前台调用也用克隆出来的那份：这样整条 e2e 与「开发者 shell 有没有 ~/Documents 权限」
# 完全无关——不依赖开发机的碰巧状态，是这一串 e2e 一直守的纪律。
aprod() { env "JAVA_TOOL_OPTIONS=-Duser.home=${UHOME} -Djava.io.tmpdir=${UTMP}" "$LAUNCH_JAVA" "$@"; }

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
while ! port_free "$PORT"; do
  PORT=$((PORT + 1))
  [ "$PORT" -gt 18120 ] && { bad "找不到空着的端口"; exit 1; }
done
ok "端口 ${PORT} 空着"

echo ""
echo "== 4. install：plist 写到哪、launchd 认不认、服务起没起 =="
aprod -jar "$LAUNCH_JAR" autostart install --app "$APP_DIR" --port "$PORT" > "$WORK/install.out" 2>&1
check "autostart install 退出码 0" "0" "$?"
[ -f "$PLIST_PATH" ] && ok "plist 写到了 ${PLIST_PATH}" || { bad "plist 没写到 ${PLIST_PATH}"; sed -n '1,20p' "$WORK/install.out"; exit 1; }

# plist 的合法性交给系统自带的 plutil 判（不经过本项目的任何代码）。
if plutil -lint "$PLIST_PATH" > "$WORK/plutil.out" 2>&1; then
  ok "plist 通过 plutil 校验（launchd 读得进去）"
else
  bad "plist 没通过 plutil 校验"; sed -n '1,10p' "$WORK/plutil.out"
fi
contains "plist 里带的是 serve 子命令" "$PLIST_PATH" "<string>serve</string>"
contains "plist 里带的是 --no-browser（自启时不弹窗）" "$PLIST_PATH" "<string>--no-browser</string>"
contains "plist 里带的是固定端口" "$PLIST_PATH" "<string>${PORT}</string>"
contains "plist 里 RunAtLoad 为真" "$PLIST_PATH" "<key>RunAtLoad</key>"

check "launchd 里服务已装载" "yes" "$(svc_loaded)"
PID1="$(svc_pid)"
[ -n "$PID1" ] && ok "launchd 报出了进程号 ${PID1}" || bad "launchd 没报出进程号"
check "刚装上时只跑过一次" "1" "$(svc_runs)"

# 端口真的在监听、HTTP 真的有响应。
# 【为什么轮询而不是 sleep 固定几秒】JVM 冷启动要 1~4 秒（实测波动明显），
# 固定 sleep 2 秒会偶发地「服务还没起来就断言」——那是**判据自己不稳**，
# 不是产品坏了。所以这里按真实条件轮询，判据才立得住。
CODE="000"
for _ in $(seq 1 30); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:${PORT}/api/health")"
  [ "$CODE" = "200" ] && break
  sleep 1
done
check "服务在监听 ${PORT}（/api/health 返回 200）" "200" "$CODE"

aprod -jar "$LAUNCH_JAR" autostart status > "$WORK/status.out" 2>&1
check "autostart status 退出码 0" "0" "$?"
contains "status 说服务已装载" "$WORK/status.out" "已装载"
contains "status 报出了进程号" "$WORK/status.out" "$PID1"

echo ""
echo "== 5. 崩了要自愈（KeepAlive 无条件 true） =="
# kill -9 = 被信号终止 = 崩了。launchd 该把它重新拉起来。
#
# 【为什么这里要等这么久】plist 里 ThrottleInterval=10 秒（重试频率上限），
# 所以崩溃后最多要等 10 秒才重新拉起，再加上 JVM 启动时间。
# → 判据要按**真实时延**来等，不能拍脑袋等 2 秒。
kill -9 "$PID1" >/dev/null 2>&1
NEWPID=""
for _ in $(seq 1 45); do
  sleep 1
  P="$(svc_pid)"
  if [ -n "$P" ] && [ "$P" != "$PID1" ]; then NEWPID="$P"; break; fi
done
if [ -n "$NEWPID" ]; then
  ok "崩溃后 launchd 重新拉起了服务（${PID1} → ${NEWPID}）"
  # 等它真的能对外服务（JVM 启动 + 绑定端口）
  CODE="000"
  for _ in $(seq 1 30); do
    CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:${PORT}/api/health")"
    [ "$CODE" = "200" ] && break
    sleep 1
  done
  check "自愈后端口又通了" "200" "$CODE"
else
  bad "崩溃后服务没有被重新拉起（pid 一直是 ${PID1}）"
fi

echo ""
echo "== 6. 端口被占时：服务起不来，**端口一腾出来就自动就位** =="
# 这一条是第 4 条的**对照**：同一份 plist，一个该重启、一个该重试到成功为止。
#
# 【实测得来的语义】本机 launchd：KeepAlive 无条件 true 时，
# 服务因端口被占而正常退出，launchd 也会按 ThrottleInterval 把它再拉一次。
# 反复失败，直到端口腾出来 → 服务自动就位，**不用用户再装一次**。
# （原来想用 {Crashed:true} 只在崩溃时重启，实测那玩意从不重启，见阶段文档。）
launchctl bootout "gui/${UID_NUM}/${LABEL}" >/dev/null 2>&1
BUSY_PORT=$((PORT + 40))
while ! port_free "$BUSY_PORT"; do
  BUSY_PORT=$((BUSY_PORT + 1))
  [ "$BUSY_PORT" -gt 18160 ] && { bad "找不到第二个空端口"; exit 1; }
done
python3 -c '
import socket, sys, time
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", int(sys.argv[1])))
s.listen(5)
time.sleep(180)
' "$BUSY_PORT" &
OCCUPIER_PID=$!
sleep 1
if port_free "$BUSY_PORT"; then
  bad "占位程序没能占住 ${BUSY_PORT}（这一条的前提不成立）"
else
  ok "占位程序占住了 ${BUSY_PORT}"
fi

aprod -jar "$LAUNCH_JAR" autostart install --app "$APP_DIR" --port "$BUSY_PORT" > "$WORK/install-busy.out" 2>&1
check "端口被占时 install 仍退出 0（plist 本身没问题，是端口的问题）" "0" "$?"
# 等它真的重试过：plist 里 ThrottleInterval=10（重试频率上限），
# 所以第二次尝试最早也要 10 秒之后。这里**轮询**而不是固定 sleep 14 ——
# 固定睡法在机器慢一点时就会踩在重试窗口外，判据自己不稳。
# 轮询到「runs 至少 2」为止，最多等 40 秒。
RUNS_BUSY=0
for _ in $(seq 1 40); do
  RUNS_BUSY="$(svc_runs)"
  [ "${RUNS_BUSY:-0}" -ge 2 ] && break
  sleep 1
done
if [ "${RUNS_BUSY:-0}" -ge 2 ]; then
  ok "端口被占时服务试了 ${RUNS_BUSY} 次（它在等端口腾出来）"
else
  bad "端口被占时服务没有重试（runs=${RUNS_BUSY}）—— 端口腾出来后它不会自己就位"
fi
if [ -f "$UHOME/.litemask/logs/serve.err.log" ]; then
  contains "日志里说清了端口被占" "$UHOME/.litemask/logs/serve.err.log" "已被占用"
else
  bad "没有错误日志（无法确认服务是因为端口被占而退出的）"
fi

echo "   —— 现在把端口腾出来 ——"
kill "$OCCUPIER_PID" >/dev/null 2>&1
OCCUPIER_PID=""
CODE="000"
for _ in $(seq 1 45); do
  sleep 1
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:${BUSY_PORT}/api/health")"
  [ "$CODE" = "200" ] && break
done
check "端口腾出来后服务自动就位（不用再装一次）" "200" "$CODE"

echo ""
echo "== 7. uninstall：服务与配置都要没 =="
launchctl bootout "gui/${UID_NUM}/${LABEL}" >/dev/null 2>&1
aprod -jar "$LAUNCH_JAR" autostart install --app "$APP_DIR" --port "$PORT" > "$WORK/install2.out" 2>&1
check "重新装上（回到正常端口）" "0" "$?"
sleep 2
check "重新装上后服务已装载" "yes" "$(svc_loaded)"

aprod -jar "$LAUNCH_JAR" autostart uninstall > "$WORK/uninstall.out" 2>&1
check "autostart uninstall 退出码 0" "0" "$?"
[ -f "$PLIST_PATH" ] && bad "uninstall 之后 plist 应该没了" || ok "uninstall 之后 plist 没了"
# 【为什么这里要轮询，不能立刻查】
# `launchctl bootout` 发完 SIGTERM 就**立刻返回**，但 launchd 侧的记账要滞后约 1 秒
# （实测：真跑起来的 JVM 服务，bootout 后 +0s 还 print 得到、state=SIGTERMed，
#   +1.1s 才 GONE）。立刻查必然踩在这个窗口里 —— 那是**判据自己不稳**，不是产品坏了。
# 所以这里按真实条件轮询，等它真的消失。
GONE=""
for _ in $(seq 1 20); do
  [ "$(svc_loaded)" = "no" ] && { GONE="no"; break; }
  sleep 1
done
check "uninstall 之后 launchd 里没有这个服务了" "no" "${GONE:-yes}"
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:${PORT}/api/health")"
if [ "$CODE" = "200" ]; then bad "uninstall 之后端口还在提供服务"; else ok "uninstall 之后端口不通了"; fi

echo ""
echo "== 8. 幂等：再卸一次不该报错 =="
aprod -jar "$LAUNCH_JAR" autostart uninstall > "$WORK/uninstall2.out" 2>&1
check "第二次 uninstall 退出码 0" "0" "$?"
contains "第二次 uninstall 说清本来就没装" "$WORK/uninstall2.out" "本来就没装"

echo ""
echo "== 9. 工具卸载（uninstall）也必须把自启停掉 =="
# 这一条验的是另一条路径：不是 autostart uninstall，而是**卸载整个工具**。
# 缺陷形态是「程序卸载了，但 launchd 里还留着常驻服务，开机还会自己起来」。
aprod -jar "$LAUNCH_JAR" autostart install --app "$APP_DIR" --port "$PORT" > "$WORK/install3.out" 2>&1
check "装上自启" "0" "$?"
sleep 2
check "自启已装载" "yes" "$(svc_loaded)"
[ -f "$PLIST_PATH" ] && ok "plist 在位" || bad "plist 不在位"

aprod -jar "$LAUNCH_JAR" uninstall > "$WORK/tool-uninstall.out" 2>&1
check "uninstall 退出码 0" "0" "$?"
[ -f "$PLIST_PATH" ] && bad "uninstall 之后 plist 应该没了" || ok "uninstall 之后 plist 没了"
# 同样要轮询：bootout 的记账滞后约 1 秒（见第 7 节的实测）。
# 【为什么这里原来「碰巧」是绿的】它紧跟在第 7 节之后，launchd 的记账早已滞后完毕，
# 于是立刻查就查不到残留 —— 那是**运气**，不是判据稳。两条路径的判据必须一样。
GONE9=""
for _ in $(seq 1 20); do
  [ "$(svc_loaded)" = "no" ] && { GONE9="no"; break; }
  sleep 1
done
check "uninstall 之后 launchd 里没有这个服务了" "no" "${GONE9:-yes}"
contains "uninstall 说清它停了常驻服务" "$WORK/tool-uninstall.out" "开机自启"

echo ""
echo "== 10. label 里的路径分隔符一律拒绝 =="
aprod -jar "$LAUNCH_JAR" autostart install --app "$APP_DIR" --label "../../evil" > "$WORK/traversal.out" 2>&1
check "带路径分隔符的 label 退出码 2" "2" "$?"
OUTSIDE="$(cd "$UHOME/Library/LaunchAgents" 2>/dev/null && cd .. && cd .. && pwd)/evil.plist"
[ -f "$OUTSIDE" ] && bad "不该在 LaunchAgents 之外落文件: $OUTSIDE" || ok "没有在 LaunchAgents 之外落文件"

echo ""
echo "== 11. 端口被占时：status 必须说「服务不可用」（块 23）=="
# 【这个缺陷是实测复现的 —— 2026-10-21，macOS 27.2.0】
#   占住端口后装自启，launchd 认得这个服务（KeepAlive 反复拉起），
#   但服务每次起来都立刻退出 → /api/health 是 timeout。
#   老 status 此时报「运行状态 已装载」、退出码 0
#   → 用户据此以为服务在跑，界面却打不开。
#
# 【这一节复用第 6 节留下的现场】
#   第 6 节把端口腾开之后，服务就自动就位了 —— 所以这里要**重新占一次**。
#   重新占的是同一个 BUSY_PORT（plist 里的 --port 还是它），
#   这样 status 读到的端口（来自 plist）就是真正被占着的那个。
launchctl bootout "gui/${UID_NUM}/${LABEL}" >/dev/null 2>&1
python3 -c '
import socket, sys, time
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", int(sys.argv[1])))
s.listen(5)
time.sleep(120)
' "$BUSY_PORT" &
OCCUPIER_PID=$!
sleep 1
if port_free "$BUSY_PORT"; then
  bad "占位程序没能占住 ${BUSY_PORT}（这一条的前提不成立）"
else
  ok "占位程序占住了 ${BUSY_PORT}"
fi

# 装上（plist 指向被占的端口）
aprod -jar "$LAUNCH_JAR" autostart install --app "$APP_DIR" --port "$BUSY_PORT" > "$WORK/install-busy2.out" 2>&1
check "端口被占时 install 仍退出 0" "0" "$?"
# 等它真的重试过（ThrottleInterval=10），确认场景成立：launchd 认得它。
RUNS_BUSY2=0
for _ in $(seq 1 40); do
  RUNS_BUSY2="$(svc_runs)"
  [ "${RUNS_BUSY2:-0}" -ge 2 ] && break
  sleep 1
done
if [ "${RUNS_BUSY2:-0}" -ge 2 ]; then
  ok "launchd 在反复拉起起不来的服务（runs=${RUNS_BUSY2}）—— 现场成立"
else
  bad "launchd 没有重试（runs=${RUNS_BUSY2}）—— 现场不成立，这条测不到东西"
fi

# 前提自证：端口确实不通（否则「不可用」就是假红）
CODE_BUSY="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:${BUSY_PORT}/api/health")"
if [ "$CODE_BUSY" = "000" ]; then
  ok "前提成立：端口 ${BUSY_PORT} 上没有服务在应答（curl=${CODE_BUSY}）"
else
  bad "前提不成立：端口 ${BUSY_PORT} 居然有应答（curl=${CODE_BUSY}）—— 这一条测不到东西"
fi

# ---- 核心断言 ----
aprod -jar "$LAUNCH_JAR" autostart status > "$WORK/status-busy.out" 2>&1
RC_BUSY=$?
check "status 在服务不可用时退出码非 0（脚本要能据此报警）" "nonzero" "$( [ "$RC_BUSY" -ne 0 ] && echo nonzero || echo zero )"
contains "status 说服务检查 不可用" "$WORK/status-busy.out" "服务检查 不可用"
# 【为什么这里不断言具体是哪个原因词 —— 2026-10-21 实测踩到的坑】
#   早先写的是 `contains ... "连不上"`，实跑得到的却是
#     原因     连上了但没响应（超时 1500 毫秒）
#   因为占位程序是 socket.listen() —— **TCP 连得上、只是不应答**，
#   走的是「超时」分支而不是「连接被拒」分支。
#   → 断言写死一个原因词 = 断言了现场的偶然形态 → 现场一变就假红。
#   → 判据落在「**给出了原因**」这件事上：
#       有一行以「原因」开头，且那一行**有内容**。
#     具体是「连不上」还是「超时」由现场决定，两者都是对的。
REASON_LINE="$(grep -E '^[[:space:]]*原因' "$WORK/status-busy.out" | head -1 | sed -E 's/^[[:space:]]*原因[[:space:]]+//')"
if [ -n "$REASON_LINE" ]; then
  ok "status 说了不可用的原因：${REASON_LINE}"
else
  bad "status 没有说不可用的原因（输出一行里没找到「原因 …」）"
  sed -n '1,20p' "$WORK/status-busy.out" | sed 's/^/    | /'
fi
contains "status 给出可核对的下一步" "$WORK/status-busy.out" "/api/health"
# 「已装载」这句仍然要留着 —— launchd 确实认得它，那是事实。
contains "status 仍如实报出 launchd 的装载状态" "$WORK/status-busy.out" "已装载"

# 收尾：把端口腾开，别把占位程序留到脚本结束
kill "$OCCUPIER_PID" >/dev/null 2>&1
OCCUPIER_PID=""

echo ""
echo "=== e2e-autostart: pass=$pass fail=$fail ==="
[ "$fail" -eq 0 ] || exit 1
exit 0
