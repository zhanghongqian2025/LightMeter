#!/usr/bin/env bash
# 命令行帮助「处处成立」的端到端。
#
# 【这一块为什么单独立一个 e2e】
#   块 38 开工前的实测（真二进制）：
#     rules --help          退出码 0、stderr 全空，stdout 是一张规则表
#                          —— 它**静默执行了 rules**。用户以为看到了用法，
#                             其实跑了一条命令，而且**看不出任何异常**。
#     redact 合同.txt --help
#                          **真的把合同脱敏了**（产出 .redacted.txt 与映射件），
#                          最后才因为「找不到文件 --help」报 rc=1。
#     redact --ouput 合同.txt
#                          报「错误: --ouput」—— 那其实是「文件 --ouput 不存在」
#                          的异常消息：解析层把未知开关当成了**文件名**。
#     restore/serve/uninstall/autostart --help
#                          报「错误」并退出码 2（用户看到的是错误，不是用法）。
#
# 【这个 e2e 测的是「help 说不做事，就真的不做事」】
#   守卫（work/check-cli-help-everywhere.py）做的是**文本层**的比对。
#   那一层证明不了「--help 会不会顺手把活干了」——
#   文本可以对，行为可以对不上（比如它照样把合同脱敏了一遍）。
#   所以这里补一层**行为探针**：每个子命令的 --help 真跑一遍，
#   要求它**给用法、退出码 0、stderr 全空、工作目录一个文件都不多**。
#
# 【判据独立于被测实现】
#   所有事实都来自**跑真二进制**的输出；解析用 awk/shell 另写一份
#   （守卫那份是 python），不复用被测的任何 Java 代码。
#
# 【退出码】
#   0  = 全部跑完且通过
#   1  = 有断言失败
#   77 = 环境不具备（没有打包好的 jar）——**不是通过**
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
JAR="$ROOT/target/desens-core.jar"
IDENTITY="$ROOT/tools/package-identity.properties"
WORK="$(mktemp -d)"
pass=0; fail=0

ok()  { echo "PASS $1"; pass=$((pass+1)); }
bad() { echo "FAIL $1"; fail=$((fail+1)); }
check(){ # $1=名称 $2=期望 $3=实际
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 $2，实际 $3）"; fi
}

. "$ROOT/tools/e2e-common.sh"

# ---- 前提 ----
if [ ! -f "$JAR" ]; then
  echo "SKIP 没有打包好的 jar（$JAR），跑不了。"
  exit 77
fi
if [ ! -f "$IDENTITY" ]; then
  echo "FAIL 找不到身份表（$IDENTITY）" >&2
  exit 1
fi

# 版本号取自**跨端唯一真源**，不在这里写死一个字面量。
APP_VERSION="$(sed -n 's/^app.version=//p' "$IDENTITY" | head -1)"
if [ -z "$APP_VERSION" ]; then
  echo "FAIL $IDENTITY 里读不到 app.version" >&2
  exit 1
fi

# JVM 会往 stderr 打一行「Picked up JAVA_TOOL_OPTIONS: …」。
# 那是 **JVM 自己**的噪声，不是产品输出 —— 判「stderr 全空」时必须先摘掉它，
# 否则这条断言恒红（恒红的断言等于没有断言）。
strip_jvm_notice() { # $1=文件
  grep -v "^Picked up JAVA_TOOL_OPTIONS:" "$1" 2>/dev/null || true
}

# 子命令清单：这里写死的是**子命令的名字**（7 个），
# 而它们各自的命令行与开关是跑真二进制拿到的（不手抄）。
SUBS="redact restore rules serve uninstall autostart model"
# 这五个自己解析参数，所以要能报「未知开关」。
PARSING_SUBS="redact restore rules serve uninstall"

echo "== 1. 每个子命令的 --help：给用法、退出码 0、stderr 全空、什么都不做 =="
for sub in $SUBS; do
  d="$WORK/help-$sub"; mkdir -p "$d"
  ( cd "$d" && prod -jar "$JAR" "$sub" --help ) > "$WORK/$sub.out" 2> "$WORK/$sub.raw"
  rc=$?
  check "$sub --help 退出码" "0" "$rc"

  if grep -q "用法:" "$WORK/$sub.out"; then
    ok "$sub --help 的 stdout 给出法"
  else
    bad "$sub --help 的 stdout 没有「用法:」（stdout：$(head -3 "$WORK/$sub.out")）"
  fi

  strip_jvm_notice "$WORK/$sub.raw" > "$WORK/$sub.err"
  if [ -s "$WORK/$sub.err" ]; then
    bad "$sub --help 的 stderr 不该有内容：$(head -2 "$WORK/$sub.err")"
  else
    ok "$sub --help 的 stderr 全空"
  fi

  produced=$(ls -A "$d" | wc -l | tr -d ' ')
  check "$sub --help 不动工作目录" "0" "$produced"
done

echo ""
echo "== 2. rules --help 绝不能静默执行 rules =="
# 规则表的表头里有这一列名：只要 stdout 里有它，就说明 rules 被真的执行了。
if grep -q "占位前缀" "$WORK/rules.out"; then
  bad "rules --help 打出了规则表 —— 它静默执行了 rules，而用户以为自己在看用法"
else
  ok "rules --help 没有执行 rules"
fi

echo ""
echo "== 3. redact 带着 --help 时不许真的脱敏 =="
d="$WORK/redact-doc"; mkdir -p "$d"
printf '姓名 张三 身份证 110101199003074567\n' > "$d/合同.txt"
( cd "$d" && prod -jar "$JAR" redact 合同.txt --help ) > "$WORK/redact-doc.out" 2> "$WORK/redact-doc.raw"
rc=$?
check "redact 合同.txt --help 退出码" "0" "$rc"
produced=$(ls -A "$d" | grep -c "redacted" || true)
check "redact 合同.txt --help 不产出脱敏件" "0" "$produced"

echo ""
echo "== 4. 未知开关：当场报成未知开关（口径统一）+ 退出码非 0 =="
for sub in $PARSING_SUBS; do
  d="$WORK/unk-$sub"; mkdir -p "$d"
  ( cd "$d" && prod -jar "$JAR" "$sub" --zzz-not-a-real-option ) > "$WORK/unk-$sub.out" 2> "$WORK/unk-$sub.raw"
  rc=$?
  if [ "$rc" -eq 0 ]; then
    bad "$sub 对未知开关的退出码是 0"
  else
    ok "$sub 对未知开关的退出码非 0"
  fi
  strip_jvm_notice "$WORK/unk-$sub.raw" > "$WORK/unk-$sub.err"
  if grep -q "未知开关" "$WORK/unk-$sub.err"; then
    ok "$sub 把未知开关报成「未知开关」"
  else
    bad "$sub 对未知开关的报错口径不一致：$(head -1 "$WORK/unk-$sub.err")"
  fi
done

echo ""
echo "== 5. 拼错的开关：不被当成文件名 + 给出建议 =="
d="$WORK/typo"; mkdir -p "$d"
printf '姓名 张三 身份证 110101199003074567\n' > "$d/合同.txt"
( cd "$d" && prod -jar "$JAR" redact 合同.txt --ouput 合同.txt ) > "$WORK/typo.out" 2> "$WORK/typo.raw"
rc=$?
if [ "$rc" -eq 0 ]; then
  bad "拼错的开关居然退出码 0"
else
  ok "拼错的开关退出码非 0"
fi
strip_jvm_notice "$WORK/typo.raw" > "$WORK/typo.err"
if grep -q "未知开关" "$WORK/typo.err"; then
  ok "拼错的开关被报成「未知开关」"
else
  bad "拼错的开关没被报成「未知开关」：$(head -1 "$WORK/typo.err")"
fi
if grep -q -- "--output" "$WORK/typo.err"; then
  ok "拼错的开关给出了「你是不是想写 --output」的建议"
else
  bad "拼错的开关没有给出建议：$(head -1 "$WORK/typo.err")"
fi
produced=$(ls -A "$d" | grep -c "redacted" || true)
check "拼错的开关没让文档被脱敏" "0" "$produced"

echo ""
echo "== 6. 顶层 help 的版本号现算 =="
( cd "$WORK" && prod -jar "$JAR" --help ) > "$WORK/top.out" 2> "$WORK/top.raw"
first_line="$(head -1 "$WORK/top.out")"
case "$first_line" in
  *"$APP_VERSION"*) ok "顶层 help 顶部的版本是身份表里的 $APP_VERSION" ;;
  *) bad "顶层 help 顶部的版本不是身份表里的 $APP_VERSION：$first_line" ;;
esac
case "$first_line" in
  *"M3-"*) bad "顶层 help 顶部还写着阶段标签：$first_line（版本号必须现算）" ;;
  *) ok "顶层 help 顶部没有写死的阶段标签" ;;
esac

echo ""
echo "== 7. 前提自证 =="
# 用法块不能是空的：解析器坏掉时上面那些断言会**全部空转**
n=$(awk '/用法:/{f=1;next} f&&/^[[:space:]]*$/{f=0} f&&NF{c++} END{print c+0}' "$WORK/top.out")
if [ "$n" -eq 0 ]; then
  bad "前提自证：顶层 help 的用法块解析出 0 行 —— 上面那些断言等于没跑"
else
  ok "前提自证：顶层 help 的用法块解析出 $n 行"
fi
for sub in $SUBS; do
  n=$(awk '/用法:/{f=1;next} f&&/^[[:space:]]*$/{f=0} f&&NF{c++} END{print c+0}' "$WORK/$sub.out")
  if [ "$n" -eq 0 ]; then
    bad "前提自证：$sub --help 的用法块解析出 0 行"
  else
    ok "前提自证：$sub --help 的用法块解析出 $n 行"
  fi
done

echo ""
echo "=== e2e-cli-help-everywhere: pass=${pass} fail=${fail} ==="
rm_rf "$WORK"
[ "$fail" -eq 0 ] || exit 1
exit 0
