#!/usr/bin/env bash
# 「文档内容零外发」的可执行证明。
#
# 【这一条为什么值得单独立一个块】
# 全局 DoD 里有四条：核心包 ≤90MB、五种格式全覆盖、**文档内容零外发**、
# 双平台安装包能在干净机器上跑通。
# 「零外发」是**这个工具对律所的承诺本身**——它是「文档不出本机」的商业前提，
# 不是一条技术指标。以前它只存在于：
#   - src/main/java/com/litemask/server/Workspace.java 里的一句注释；
#   - 文档里的若干句声明；
#   - 「网络 API 只出现在 ModelFetcher 与 ServiceProbe」这种**靠人记住**的约定。
# **没有任何一条检查会因为「有人往脱敏路径里加了一次 HTTP 请求」而变红。**
# 这一块把承诺变成**会红的检查**。
#
# 【两层证明，互相补位，缺一不可】
#   动态层（本脚本）：在**网络被系统调用层封死**的沙箱里跑完整脱敏 + 还原往返。
#       它证明「被跑到的那些路径真的不需要网络」，而且是**系统级**的封网，
#       Java 层的绕法绕不过，原生库（ONNX Runtime）也一并被拦。
#       但它**只能覆盖被 e2e 走到的分支**。
#   静态层（tools/verify_no_network.py）：扫常量池，看**谁会真的发起出站网络 I/O**。
#       它覆盖「没被走到的分支」，但只看得到**直接调用 JDK 网络 API** 的地方。
# 两层一起才够；任何一层单独成立都不足以说「零外发已被证明」。
#
# 【沙箱自证为什么必须放在最前面 —— 防恒真】
# 「沙箱里跑通了」这句话本身可能是**恒真**的：如果 sandbox-exec 其实没封住网
# （profile 写错、平台不支持、被环境绕开），那「跑通」只说明「这台机器当时能上网」。
# 所以必须先证明三件事，全对才允许往下走：
#   0a 沙箱**外**：同一个探针连得上 → 网络路径本身是好的
#   0b 沙箱**内**：同一个探针连不上，且 errno 是 **EPERM/EACCES**（被主动拒绝）
#      —— 只看「连不上」不够：ECONNREFUSED（对面没人）、ETIMEDOUT（真断网）
#      都与「被封」无关，证据力完全不同
#   0c 沙箱**内**：文件仍然可写可读 → 证明沙箱只封了网络，没有把执行环境一起封死
#      （否则「跑通」也可能只是「什么都没跑」）
#
# 【平台】
# sandbox-exec 是 macOS 的东西。别的平台上本脚本**直接失败并说明**，
# 不静默跳过——「没验」不是「验过」。
#
# 用法：
#   ./tools/e2e-zero-egress.sh
#   LITEMASK_E2E_NER=<模型目录> ./tools/e2e-zero-egress.sh   # 带模型形态
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
MVN="$MVN_BIN -B -o -Dstyle.color=never"
JAR="$ROOT/target/desens-core.jar"
PROBE="$ROOT/tools/net_probe.py"

# 形态（模型）与网络封死都从这里进；prod 是**产品调用的唯一出口**。
export LITEMASK_SANDBOX_NET_DENY=1
. "$ROOT/tools/e2e-common.sh"
e2e_form_banner

WORK="$(mktemp -d)"
LISTENER_PID=""
cleanup() {
  # 杀监听口时把 bash 的「Terminated」提示静音掉：
  # 那是 job control 的收尾消息，不是失败信号，
  # 混在输出里会让人以为这一步出了岔子。
  [ -n "$LISTENER_PID" ] && kill "$LISTENER_PID" 2>/dev/null
  wait "$LISTENER_PID" 2>/dev/null
  rm_rf "$WORK"
}
trap cleanup EXIT

pass=0; fail=0
check() { # check <描述> <条件退出码>
  if [ "$2" -eq 0 ]; then echo "PASS $1"; pass=$((pass+1))
  else echo "FAIL $1"; fail=$((fail+1)); fi
}

echo ""
echo "=============================================================="
echo " 第 0 步 · 沙箱自证（三条全对才允许往下走）"
echo "=============================================================="
# 【为什么是 77 而不是 1 —— 2026-10-21 块 26】
#   链里对「环境不具备」的既有约定是 **77 = 跳过，不是通过**
#   （Windows 端到端驱动编译闸门那一支就是这么处理的）。
#   本脚本依赖 macOS 专有的 `sandbox-exec`；在别的平台上它**无法执行**，
#   那是**环境不具备**，不是「产品有问题」。
#   → 报 1 会让「这块没验过」被读成「这块坏了」；
#     报 0 更糟（那是撒谎）。
#   → 所以报 77，并把话说清楚：**「没验」不是「验过」。**
if [ "$(uname -s)" != "Darwin" ]; then
  echo "SKIP 本脚本依赖 macOS 的 sandbox-exec，当前系统是 $(uname -s)"
  echo "     「零外发」在别的平台上**尚未验证**——这是**明确的验证缺口**，不是通过。"
  exit 77
fi

# ---- 0a / 0b / 0c 需要一个监听口 ----
PORTFILE="$WORK/port"
python3 "$PROBE" listen "$PORTFILE" >"$WORK/listener.log" 2>&1 &
LISTENER_PID=$!
for _ in $(seq 1 50); do
  [ -s "$PORTFILE" ] && break
  sleep 0.1
done
if [ ! -s "$PORTFILE" ]; then
  echo "FAIL 本机监听口没起来：$(cat "$WORK/listener.log" 2>/dev/null)"
  exit 1
fi
PORT="$(tr -d ' \n' < "$PORTFILE")"
echo "本机监听口：127.0.0.1:$PORT"

# 0a 沙箱外：连得上
if python3 "$PROBE" connect 127.0.0.1 "$PORT" >"$WORK/outside.txt" 2>&1; then
  check "0a 沙箱外能连上本机监听口（网络路径本身是好的）" 0
else
  check "0a 沙箱外能连上本机监听口（网络路径本身是好的）" 1
  echo "     实际输出：$(cat "$WORK/outside.txt")"
fi

# 0b 沙箱内：连不上，且 errno 是 EPERM(1)/EACCES(13)
#
# 【profile 必须用变量引，不能在这里再抄一份 —— 块 32】
#   原来这三条自证都把 profile **写死**在命令行里，而产品实际跑的是
#   e2e-common.sh 里选出来的那个。两者一旦不一致，
#   自证就变成了**在测另一个 profile** → 证明的证据力指错地方。
#   → 统一从 e2e-common.sh 引（那里是**唯一定义点**）。
sandbox-exec -p "$SANDBOX_NET_PROFILE_ALL" \
  python3 "$PROBE" connect 127.0.0.1 "$PORT" >"$WORK/inside.txt" 2>&1
rc_inside=$?
errno_txt="$(awk '{print $2}' "$WORK/inside.txt" 2>/dev/null | head -1)"
if [ "$rc_inside" -ne 0 ] && { [ "$errno_txt" = "1" ] || [ "$errno_txt" = "13" ]; }; then
  check "0b 沙箱内连不上，且 errno=EPERM/EACCES（被主动拒绝，不是没人听/断网）" 0
else
  check "0b 沙箱内连不上，且 errno=EPERM/EACCES（被主动拒绝，不是没人听/断网）" 1
  echo "     退出码=$rc_inside 输出=$(cat "$WORK/inside.txt")"
fi

# 0c 沙箱内：文件仍然可写可读
if sandbox-exec -p "$SANDBOX_NET_PROFILE_ALL" \
     /bin/sh -c 'printf hello > "$1"' _ "$WORK/inside-write.txt" 2>"$WORK/inside-write.err" \
     && [ -s "$WORK/inside-write.txt" ]; then
  check "0c 沙箱内文件仍可写可读（只封了网络，没把执行环境一起封死）" 0
else
  check "0c 沙箱内文件仍可写可读（只封了网络，没把执行环境一起封死）" 1
  cat "$WORK/inside-write.err" 2>/dev/null
fi

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "X 沙箱自证没过 —— 后面的「跑通」没有证据力，停止。"
  exit 1
fi

echo ""
echo "=============================================================="
echo " 第 1 步 · 封网下跑端到端（三种格式的既有 e2e）"
echo "=============================================================="
for e in e2e-ooxml.sh e2e-pdf.sh e2e-ofd.sh; do
  echo ""
  echo "---- $e ----"
  if bash "$ROOT/tools/$e" >"$WORK/$e.log" 2>&1; then
    echo "PASS $e 在封网沙箱里通过"
    pass=$((pass+1))
  else
    echo "FAIL $e 在封网沙箱里失败"
    fail=$((fail+1))
    tail -30 "$WORK/$e.log"
  fi
  grep -E '^(PASS|FAIL|OK)' "$WORK/$e.log" | tail -8
done

echo ""
echo "=============================================================="
echo " 第 2 步 · 封网下跑 txt 管线（脱敏 + 还原往返）"
echo "=============================================================="
if [ ! -f "$JAR" ]; then
  echo "FAIL 没有 $JAR —— 第 1 步没构建出 jar"
  fail=$((fail+1))
else
  TXTW="$WORK/txt"
  mkdir -p "$TXTW"
  TXT="$TXTW/t.txt"
  printf '张三丰的身份证是11010519491231002X，手机13800138000。\n' > "$TXT"
  # 【断言必须与运行形态相称 —— 这一条第一版就栽在这里】
  #   无模型形态下，中文人名/机构名**本来就不脱敏**（产品自己会声明这一点），
  #   所以「张三丰」在无模型形态下**必须留在**脱敏件里。
  #   第一版把三个值一律列成「必须消失」，结果无模型形态下这条断言恒红——
  #   而那个红**不是产品的问题，是校验器把形态的前提当成了通则**。
  #   所以：规则类的值（身份证/手机号）任何形态都必须消失；
  #   人名只在带模型形态下才算「应该消失」。
  if [ "$E2E_FORM_LABEL" = "已启用" ]; then
    printf '{"values": ["11010519491231002X", "13800138000", "张三丰"]}\n' > "$TXTW/values.json"
  else
    printf '{"values": ["11010519491231002X", "13800138000"]}\n' > "$TXTW/values.json"
  fi

  if prod -jar "$JAR" redact "$TXT" >"$TXTW/redact.out" 2>"$TXTW/redact.err"; then
    check "txt 脱敏命令退出码 0" 0
    e2e_form_check "$TXTW/redact.out"
    RED="$TXTW/t.redacted.txt"
    MAP="$TXTW/t.txt.redacted.map.json"
    [ -f "$RED" ] && check "txt 产出脱敏件" 0 || { check "txt 产出脱敏件" 1; }
    [ -f "$MAP" ] && check "txt 产出映射" 0 || { check "txt 产出映射" 1; }
    # 【人名令牌：两种形态各断言一条，**条数相同** —— 2026-10-21 块 26】
    #   无模型形态：模型没装却产出 <人名1> → 说明模型是从别处被捡到的
    #     （没装却假装脱了人名 = 隔离失效）。
    #   带模型形态：模型装了却**没有** <人名1> → 说明人名**没被脱敏**
    #     （它可能被别的规则吃掉了，字节级校验器只查「原值消失」，查不出这个）。
    #   → 这一对断言**互为反面**，条数相同 → 两种形态的 pass 数相同
    #     → 文档与链日志引的是**同一个数**，不必写「两种形态分别是 15 和 14」。
    if [ "$E2E_FORM_LABEL" = "已启用" ]; then
      if grep -q '<人名1>' "$RED"; then
        check "带模型形态下 <人名1> 令牌在位（模型真的把人名脱了，不是被别的规则吃掉）" 0
      else
        check "带模型形态下 <人名1> 令牌在位（模型真的把人名脱了，不是被别的规则吃掉）" 1
      fi
    else
      if grep -q '<人名1>' "$RED"; then
        check "无模型形态下没有 <人名1> 令牌（模型没装就不能假装脱了人名）" 1
      else
        check "无模型形态下没有 <人名1> 令牌（模型没装就不能假装脱了人名）" 0
      fi
    fi
    if prod -jar "$JAR" restore "$RED" --map "$MAP" >"$TXTW/restore.out" 2>&1; then
      check "txt 还原命令退出码 0" 0
      RESTORED="$TXTW/t.redacted.restored.txt"
      if [ -f "$RESTORED" ] && python3 "$ROOT/tools/verify_txt_roundtrip.py" \
            --orig "$TXT" --redacted "$RED" --restored "$RESTORED" --map "$MAP" \
            --values "$TXTW/values.json"; then
        check "txt 独立校验：零原值/碎片 + 令牌已写入 + 还原逐字节一致" 0
      else
        check "txt 独立校验：零原值/碎片 + 令牌已写入 + 还原逐字节一致" 1
      fi
    else
      check "txt 还原命令退出码 0" 1
      tail -10 "$TXTW/restore.out"
    fi
  else
    check "txt 脱敏命令退出码 0" 1
    tail -20 "$TXTW/redact.err"
  fi
fi

echo ""
echo "=============================================================="
echo " 第 3 步 · 静态标尺：出站网络 / 进程外调用 / 反射入口（三个维度）"
echo "=============================================================="
if python3 "$ROOT/tools/verify_no_network.py" --jar "$JAR"; then
  check "静态标尺：三个维度的类集合与三本登记册一致" 0
else
  check "静态标尺：三个维度的类集合与三本登记册一致" 1
fi

echo ""
echo "=============================================================="
echo " 第 4 步 · 负控制（差分式）：只差「封网」这一个变量，结果必须从绿翻红"
echo "=============================================================="
# 负控制用的假产品启动器：先尝试一次出站连接，连不上就直接非 0 退出。
#   网络可用 → 连上 → 继续跑真 java（端到端应当**全绿**）
#   网络被封 → 连不上 → 立刻退出（端到端应当**变红**）
# 两次跑**只差** LITEMASK_SANDBOX_NET_DENY 这一个变量，
# 所以「绿 → 红」这个翻转就是判别力的直接证据。
WRAP="$ROOT/tools/net_attempt_wrapper.sh"
chmod +x "$WRAP"

export NET_PROBE="$PROBE"
export NET_PORT="$PORT"
export REAL_JAVA="$JAVA_HOME/bin/java"

# 4a 沙箱外（不封网）：同一个 wrapper 应当**全绿**——证明 wrapper 本身没破坏端到端
echo "---- 4a 沙箱外 + wrapper（期望：端到端通过）----"
if LITEMASK_SANDBOX_NET_DENY= LITEMASK_RUN_JAVA="$WRAP" \
     bash "$ROOT/tools/e2e-ooxml.sh" >"$WORK/neg-a-outside.log" 2>&1; then
  check "4a 沙箱外 + 会联网的 wrapper → 端到端仍通过（wrapper 本身无害）" 0
else
  check "4a 沙箱外 + 会联网的 wrapper → 端到端仍通过（wrapper 本身无害）" 1
  tail -20 "$WORK/neg-a-outside.log"
fi

# 4b 沙箱内（封网）：同一个 wrapper 必须**变红**——这才是判别力的证据
echo "---- 4b 沙箱内 + wrapper（期望：端到端失败）----"
if LITEMASK_SANDBOX_NET_DENY=1 LITEMASK_RUN_JAVA="$WRAP" \
     bash "$ROOT/tools/e2e-ooxml.sh" >"$WORK/neg-a-inside.log" 2>&1; then
  check "4b 沙箱内 + 会联网的 wrapper → 端到端失败（证明这条检查会咬）" 1
  echo "     端到端竟然通过了 —— 封网下它对网络访问**不敏感**，"
  echo "     说明「零外发」这条检查是恒真的，整个证明作废。"
  tail -20 "$WORK/neg-a-inside.log"
else
  check "4b 沙箱内 + 会联网的 wrapper → 端到端失败（证明这条检查会咬）" 0
  grep -m3 -E 'FAIL|脱敏命令非零退出' "$WORK/neg-a-inside.log" | sed 's/^/     /'
fi

echo ""
echo "=============================================================="
echo " 第 5 步 · 封网（禁出站、留回环）下跑 serve 形态（用户真正在用的界面）"
echo "=============================================================="
#
# 【这一块补的是什么 —— 块 32 的真缺陷】
#   「文档内容零外发」是产品对律所的**承诺本身**（全局 DoD 的一条）。
#   但此前这条承诺的**动态**证明只覆盖四条**无界面**管线（ooxml / pdf / ofd / txt）。
#   而律所用户实际用的是 **serve 形态**：浏览器 → 本机服务 → 脱敏 → 取回。
#   那条路径此前**不在**证明里 —— 当年用一句「零外发对 serve 不适用」搪塞过去。
#   → 那句话站不住：LocalServer/SecurityPolicy 管的是「服务安不安全」，
#     不是「会不会把文档发出去」——两个问题，不能互相顶替。
#   → 静态层虽然扫全 jar（联网类只有 ModelFetcher / ServiceProbe 两条白名单），
#     但它**只看得到直接调用 JDK 网络 API 的地方**：
#     原生库（ONNX Runtime）、间接调用、进程外调（shell 出去 curl）它都看不见。
#   → 沙箱在**系统调用层**拦，Java 层的绕法绕不过，原生库也一并被拦。
#   → 这一步就是把 serve 形态拉进封网证明。
#
# 【为什么是「禁出站、留回环」而不是「全封」】
#   serve 要在本机监听。全封连监听都不行 → 服务根本起不来 →
#   「服务起不来」会被误读成「没有外发」（红灯原因指错方向）。
#   → 所以用 deny network-outbound：出站被拒、回环照常。
#   → 并且 5a 把这两件事**分别**证明，不许混成一句「跑通了」。

# ---- 5a 沙箱自证（三条全对才允许往下走）----
# 5a-1 出站被拒（EPERM/EACCES）
sandbox-exec -p "$SANDBOX_NET_PROFILE_SERVE" \
  python3 "$PROBE" connect 127.0.0.1 "$PORT" >"$WORK/serve-inside.txt" 2>&1
rc_serve=$?
errno_serve="$(awk '{print $2}' "$WORK/serve-inside.txt" 2>/dev/null | head -1)"
if [ "$rc_serve" -ne 0 ] && { [ "$errno_serve" = "1" ] || [ "$errno_serve" = "13" ]; }; then
  check "5a-1 serve 沙箱内出站连不上，且 errno=EPERM/EACCES（出站被主动拒绝）" 0
else
  check "5a-1 serve 沙箱内出站连不上，且 errno=EPERM/EACCES（出站被主动拒绝）" 1
  echo "     退出码=$rc_serve 输出=$(cat "$WORK/serve-inside.txt")"
fi

# 5a-2 回环仍然可用（否则「服务起不来」会被误读成「没有外发」）
if sandbox-exec -p "$SANDBOX_NET_PROFILE_SERVE" python3 -c '
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
s.listen(1)
s.close()
' >"$WORK/serve-listen.txt" 2>&1; then
  check "5a-2 serve 沙箱内回环仍能 bind/listen（服务能起来；否则红灯原因会指错方向）" 0
else
  check "5a-2 serve 沙箱内回环仍能 bind/listen（服务能起来；否则红灯原因会指错方向）" 1
  cat "$WORK/serve-listen.txt" 2>/dev/null
fi

# 5a-3 文件仍然可写可读（只封了出站，没把执行环境一起封死）
if sandbox-exec -p "$SANDBOX_NET_PROFILE_SERVE" \
     /bin/sh -c 'printf hello > "$1"' _ "$WORK/serve-write.txt" 2>"$WORK/serve-write.err" \
     && [ -s "$WORK/serve-write.txt" ]; then
  check "5a-3 serve 沙箱内文件仍可写可读（只封了出站，没把执行环境一起封死）" 0
else
  check "5a-3 serve 沙箱内文件仍可写可读（只封了出站，没把执行环境一起封死）" 1
  cat "$WORK/serve-write.err" 2>/dev/null
fi

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "X serve 沙箱自证没过 —— 后面的「跑通」没有证据力，停止。"
  exit 1
fi

# ---- 5b serve 形态在 serve 沙箱里全绿 ----
SERVE_LOG="$WORK/e2e-serve-sandbox.log"
SERVE_ASSERTS=0
echo ""
echo "---- 5b serve 形态（禁出站、留回环）----"
if LITEMASK_SANDBOX_NET_DENY=serve bash "$ROOT/tools/e2e-serve.sh" >"$SERVE_LOG" 2>&1; then
  n_serve="$(sed -n 's/^=== e2e serve: pass=\([0-9][0-9]*\) fail=0 ===$/\1/p' "$SERVE_LOG" | tail -1)"
  # 【子段的内部断言数**不并进**总数 —— 这是既有口径，不是新规定】
  #   「零外发端到端 N 条断言」这个 N 的口径一直是：
  #   **本脚本自己的判据条数**，其中每个**子脚本跑一次算一条**
  #   （ooxml / pdf / ofd 三个 e2e 各自内部几十条断言，从来没并进那个 15）。
  #   → serve 也算**一条**，口径不变。
  #   → 变了口径就等于把同一个数换了个意思 —— 那正是 §35.3 记的「口径混用」。
  #   子段自己的条数照样**打出来**（证据在那儿），只是不进总数。
  if [ -n "$n_serve" ]; then
    check "5b serve 形态在「禁出站、留回环」沙箱里全绿（子段自报 ${n_serve} 条断言）" 0
    SERVE_ASSERTS="$n_serve"
  else
    check "5b serve 形态在「禁出站、留回环」沙箱里全绿" 1
    echo "     （子段没报出断言数 → 证据不完整，不许含糊过去）"
    tail -20 "$SERVE_LOG"
  fi
else
  check "5b serve 形态在「禁出站、留回环」沙箱里全绿" 1
  tail -30 "$SERVE_LOG"
fi

# ---- 5c 差分式负控制：只差「封网」这一个变量，结果必须从绿翻红 ----
#
# 【为什么 serve 也要有负控制】
#   「沙箱里跑通了」这句话本身可能是**恒真**的：
#   如果 serve 在沙箱里其实**根本没跑起来**（服务起不来、脚本直接跳过），
#   那「全绿」也可能只是「什么都没跑」。
#   → 负控制证明的是：**一旦产品进程真的去连网，这条检查就会红**。
#   → 两次跑**只差** LITEMASK_SANDBOX_NET_DENY 这一个变量。
echo ""
echo "---- 5c-1 沙箱外 + 会联网的 wrapper（期望：serve 端到端通过）----"
if LITEMASK_SANDBOX_NET_DENY= LITEMASK_RUN_JAVA="$WRAP" \
     bash "$ROOT/tools/e2e-serve.sh" >"$WORK/neg-serve-outside.log" 2>&1; then
  check "5c-1 沙箱外 + 会联网的 wrapper → serve 端到端仍通过（wrapper 本身无害）" 0
else
  check "5c-1 沙箱外 + 会联网的 wrapper → serve 端到端仍通过（wrapper 本身无害）" 1
  tail -20 "$WORK/neg-serve-outside.log"
fi

echo "---- 5c-2 serve 沙箱内 + 会联网的 wrapper（期望：serve 端到端失败）----"
if LITEMASK_SANDBOX_NET_DENY=serve LITEMASK_RUN_JAVA="$WRAP" \
     bash "$ROOT/tools/e2e-serve.sh" >"$WORK/neg-serve-inside.log" 2>&1; then
  check "5c-2 serve 沙箱内 + 会联网的 wrapper → serve 端到端失败（证明这条检查会咬）" 1
  echo "     serve 端到端竟然通过了 —— 它对产品进程的网络访问**不敏感**，"
  echo "     说明「serve 也零外发」这条检查是恒真的，整个证明作废。"
  tail -20 "$WORK/neg-serve-inside.log"
else
  check "5c-2 serve 沙箱内 + 会联网的 wrapper → serve 端到端失败（证明这条检查会咬）" 0
  grep -m3 -E '服务没起来|FAIL' "$WORK/neg-serve-inside.log" | sed 's/^/     /'
fi

echo ""
echo "=============================================================="
echo " 汇总：pass=$pass fail=$fail"
echo "=============================================================="
if [ "$fail" -ne 0 ]; then
  echo "X 「文档内容零外发」的可执行证明：未通过"
  exit 1
fi
echo "PASS 「文档内容零外发」的可执行证明：封网下三种格式 + txt + serve 形态（用户真正在用的界面）"
echo "     全部脱敏与还原通过，联网类集合与白名单一致，"
echo "     且这条检查对「产品进程发起网络访问」确实会红。"
exit 0
