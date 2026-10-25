#!/usr/bin/env bash
# e2e 共用引导：把「跑在什么形态」变成**显式开关**，不再由开发机的碰巧状态决定。
#
# 【为什么必须隔离 —— 2026-10 实测事故】
#   产品按 user.home 找 ~/.litemask/models/ner。按 README 在本机装上 v9 模型后，
#   5 个 e2e 里有 4 个变红（ooxml/untrusted/pdf/serve）；把模型移走又全绿。
#   这两次都不是「端到端通过」，而是「端到端测了当时那台机器的碰巧状态」。
#   和 surefire 那次是同一个病：闸门量的是开发机，不是被测代码。
#
# 【为什么不能全局 export JAVA_TOOL_OPTIONS —— 实测】
#   它会被同一条命令里的 $MVN 继承，Maven 的 user.home 也被改到隔离目录，
#   离线构建直接 PluginResolutionException（那个目录下没有 .m2）。
#   所以只包住「产品那几次调用」：夹具（样本生成器/校验器/验证脚本）仍用真实环境。
#
# 【两种形态】
#   默认（不设 LITEMASK_E2E_NER）  = 无模型形态，user.home 指向空的临时目录。
#                                    这正是 pkg 交付形态：pkg 里不含模型（方案 B）。
#   LITEMASK_E2E_NER=<目录>          = 带模型形态，用 --ner-model 那条查找路径精确指定。
#
# 【为什么带模型形态不能靠「指向空目录」】
#   LITEMASK_NER_MODEL 指向不存在/无效的目录时，NerModelRegistry.loadOrThrow 会抛
#   （fail-closed，这是对的：用户显式指定了却找不到，不能假装没装）。
#   「没装」只能由「查找路径上一个都没有」表示，也就是隔离 user.home。
#
# 用法：
#   ./tools/e2e-pdf.sh                                          # 无模型形态
#   LITEMASK_E2E_NER="$W/ner-model-v9" ./tools/e2e-pdf.sh      # 带 v9 模型形态

# 删目录的可靠实现（见 tools/shell-lib.sh 里的原因）。
. "$ROOT/tools/shell-lib.sh"

# ---- 形态开关 ----
# 不设/设空 = 无模型。设了 = 用该模型目录。
LITEMASK_E2E_NER="${LITEMASK_E2E_NER:-}"

# 隔离用的假 home。带模型形态也要设：那样「自动查找」那条路彻底不可达，
# 跑出来的东西只可能是 LITEMASK_NER_MODEL 指定的那份，不会混进开发机上的模型。
#
# 用 $$ 命名有个必须堵的口子：PID 会被复用，同名目录可能是上一次跑留下的。
# 本工具从不往这个目录里写模型，但「上一次恰好有个同名目录」这种假设一旦成立，
# 隔离就静默失效而 e2e 照样绿——正是本项目反复吃亏的那一类。所以先清空再建。
#
# ${TMPDIR} 在 macOS 上**以斜杠结尾**（/var/folders/xx/T/）。直接拼 "${TMPDIR}/xxx"
# 会得到 ".../T//xxx"（双斜杠），而产品打印路径时走的是 java.nio.file.Path，
# 它会把双斜杠**归一成单斜杠**。于是「e2e 里的路径字符串」与「产品打印的路径」
# 长得不一样，拿来做字符串比对必然对不上——曾经就是这么误判过一次。
# 所以这里先削掉尾部斜杠，让两边从一开始就是同一个字符串。
E2E_HOME="${TMPDIR:-/tmp}"
E2E_HOME="${E2E_HOME%/}/litemask-e2e-home.$$"
[ -e "$E2E_HOME" ] && rm_rf "$E2E_HOME"
mkdir -p "$E2E_HOME"
# 各脚本自己装了 trap EXIT 再做清理（一个脚本只能有一个 trap，覆盖不得），
# 所以这里不注册。这个目录建在 TMPDIR 下、且始终为空，由系统清理，不留痕迹。

if [ -n "$LITEMASK_E2E_NER" ]; then
  export LITEMASK_NER_MODEL="$LITEMASK_E2E_NER"
  [ -d "$LITEMASK_NER_MODEL" ] || {
    echo "FAIL LITEMASK_E2E_NER 指向的目录不存在: $LITEMASK_NER_MODEL" >&2
    exit 1
  }
  E2E_FORM_LABEL="已启用"
  E2E_FORM_DESC="模型包 ${LITEMASK_NER_MODEL}（user.home 同时隔离到 ${E2E_HOME}）"
else
  # 不设 LITEMASK_E2E_NER 时必须把环境变量**清掉**：它的优先级高于 user.home，
  # 开发者 shell 里若残留一个，隔离就会失效，而 e2e 照样全绿。
  unset LITEMASK_NER_MODEL
  E2E_FORM_LABEL="未启用"
  E2E_FORM_DESC="隔离 user.home=${E2E_HOME}（该目录下没有模型）"
fi

# 产品调用统一走这个数组，隔离逻辑只有这一处字面量（两处各写一遍必然漂移）。
#
# 必须是**外部命令**形式，不能只包一个 shell 函数：实测 `func ... &` 时
# bash 会 fork 出一个子 shell，$! 拿到的是**子 shell 的 PID**，而不是真正进程的
# （探针：$!=94874 子 shell / 真正进程 94876，kill $! 打不到）。
# 后果是 e2e-serve 的「停止后工作目录整棵删除」红——服务收不到 SIGTERM。
# 前台调用 PID 无关，可以用 prod 包一层；**后台调用必须展开数组**。
# 用 env 而不是 `VAR=val cmd`：数组里存不了「赋值 + 命令」这种语法。
#
# ---- 网络封死模式（LITEMASK_SANDBOX_NET_DENY）----
#
# 目的：把「文档内容零外发」从**承诺**变成**可执行的证明**——
# 在网络被封死的沙箱里跑完整脱敏 + 还原往返，全部通过
# = 脱敏过程不需要网络 = 文档内容没有离开这台机器。
#
# 为什么用 sandbox-exec，而不是「连不上就当没联网」：
#   - 「连不上」可能是「本来就没网」，也可能是「被封了」，
#     这两者的证明力完全不同（前者可能只是这台机器断网）；
#   - sandbox-exec 在**系统调用层**拒绝，Java 层的任何绕法都绕不过；
#   - 它拦的是**全部网络**（含原生库，例如 ONNX Runtime 里的东西）。
#
# ---- 三种模式（块 32 起从布尔扩成三态）----
#   空    = 不封网（普通进程）
#   1     = 全封：deny network*（连回环监听都不行）
#   serve = 禁出站、留回环：deny network-outbound
#
# 【为什么要有 serve 模式 —— 块 32 的真缺陷】
#   原来只有「全封」一种。而全封把**回环监听**也封了 → serve 形态跑不起来。
#   于是当年在下面写下了一句取舍：
#     「全封语义没有歧义。代价是 serve 模式（要在本机监听）跑不了——
#       那条路径按设计就是**本机服务**，「零外发」对它不适用，
#       由 LocalServer/SecurityPolicy 的既有判据负责。」
#   → 那是一个**未经论证的取舍**：serve 恰恰是**用户真正在用的那个界面**，
#     把承诺里最大的一块留在「零外发」的证明之外，是拿一句「不适用」搪塞掉的洞。
#     （LocalServer/SecurityPolicy 管的是「服务安不安全」，不是「会不会外发」——
#      两者是不同的问题，不能互相顶替。）
#   → 块 32 实测（sandbox profile 的语义必须实测，不能靠注释）：
#       `(deny network-outbound)` **允许** bind / listen / accept / send，
#       **禁止** connect（errno=EPERM）→ 正好是「禁出站、留回环」的精确语义。
#     （原来那条注释记的坑是：`(remote ip "localhost:*")` 匹配的是**本机所有地址**，
#      不是只有 127.0.0.1 —— 所以「按地址放行回环」这条路确实不通；
#      但「按操作放行」是通的：只禁 outbound 即可。）
#   → 于是 serve 形态可以进封网证明了。
#
# 【profile 字符串在这里定义一次，别处引用】
#   SANDBOX_NET_PROFILE_ALL   = 全封
#   SANDBOX_NET_PROFILE_SERVE = 禁出站、留回环
#   零外发脚本的第 5 步要做 serve 形态的沙箱自证，它要用同一个 profile 字符串
#   → 从这里引用，不许在别处再抄一份（抄一份 = 两处会各自漂移）。
LITEMASK_SANDBOX_NET_DENY="${LITEMASK_SANDBOX_NET_DENY:-}"
SANDBOX_NET_PROFILE_ALL='(version 1)(deny network*)(allow default)'
SANDBOX_NET_PROFILE_SERVE='(version 1)(deny network-outbound)(allow default)'
SANDBOX_NET_PROFILE=""
case "$LITEMASK_SANDBOX_NET_DENY" in
  "")
    E2E_NET_LABEL="未封网"
    E2E_NET_DESC="普通进程（本机网络可用）"
    ;;
  1)
    SANDBOX_NET_PROFILE="$SANDBOX_NET_PROFILE_ALL"
    E2E_NET_LABEL="已封网（全封）"
    E2E_NET_DESC="sandbox-exec：${SANDBOX_NET_PROFILE}"
    ;;
  serve)
    SANDBOX_NET_PROFILE="$SANDBOX_NET_PROFILE_SERVE"
    E2E_NET_LABEL="已封网（禁出站、留回环）"
    E2E_NET_DESC="sandbox-exec：${SANDBOX_NET_PROFILE}"
    ;;
  *)
    echo "FAIL 未知的封网模式：LITEMASK_SANDBOX_NET_DENY='${LITEMASK_SANDBOX_NET_DENY}'" >&2
    echo "     只认三种：空（不封）/ 1（全封）/ serve（禁出站、留回环）" >&2
    exit 1
    ;;
esac
if [ -n "$SANDBOX_NET_PROFILE" ]; then
  if ! command -v sandbox-exec >/dev/null 2>&1; then
    echo "FAIL 找不到 sandbox-exec，无法执行网络封死模式" >&2
    exit 1
  fi
  PROD_CMD=(env "JAVA_TOOL_OPTIONS=-Duser.home=${E2E_HOME}" \
            sandbox-exec -p "$SANDBOX_NET_PROFILE" "$RUN_JAVA")
else
  PROD_CMD=(env "JAVA_TOOL_OPTIONS=-Duser.home=${E2E_HOME}" "$RUN_JAVA")
fi

# 未定义时 bash 在 set -u 下直接报错，所以漏 source 不会静默通过。
prod() { "${PROD_CMD[@]}" "$@"; }

# 形态自证：产品每次启动都会自己声明模型状态（Main.java 打 registry.describe()）。
# 用产品自己的输出来确认形态，而不是相信我们设了什么——
# 「设了变量」和「变量真的被读到了」是两件事，只有后者的输出能作证。
# 形态不对时立刻退出：形态错了，后面每一条断言都在测一个不存在的东西。
e2e_form_check() { # <产品 stdout 捕获文件>
  local out="$1"
  if [ ! -s "$out" ]; then
    echo "FAIL 形态自证：没抓到产品输出，无法确认运行形态" >&2
    exit 1
  fi
  if ! grep -q "中文人名/机构名识别：${E2E_FORM_LABEL}" "$out"; then
    echo "FAIL 形态自证：期望「${E2E_FORM_LABEL}」，产品实际声明：" >&2
    grep -m1 "人名/机构名识别" "$out" >&2 || echo "  （产品没打这行声明）" >&2
    echo "  —— 隔离可能没生效，或 LITEMASK_NER_MODEL 被别处覆盖" >&2
    exit 1
  fi
  # 用 OK 而不是 PASS：这不是一条计入 pass/fail 的功能断言，而是「后面那些断言
  # 有没有意义」的前置自证。混进 PASS 会让汇总数对不上读的人。
  echo "OK 形态自证：中文人名/机构名识别=${E2E_FORM_LABEL}（${E2E_FORM_DESC}）"
}

e2e_form_banner() {
  echo "== 端到端运行形态 =="
  echo "   中文人名/机构名识别：${E2E_FORM_LABEL}"
  echo "   ${E2E_FORM_DESC}"
  echo "   网络：${E2E_NET_LABEL}（${E2E_NET_DESC}）"
}
