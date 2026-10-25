#!/usr/bin/env bash
# 卸载清理：端到端。
#
# 【这一块为什么单独立一个 e2e】
#   卸载一个脱敏工具，用户真正在意的不是「程序文件删了没」，
#   而是**磁盘上还剩什么**。本工具会留下三样东西：
#     1. 模型包（~/.litemask/models/ner，含历史版本）
#     2. 信任锚（~/.litemask/trust/anchors.json）
#     3. 任务工作区（默认 $TMPDIR/litemask-workspace）
#        ——里面是脱敏产物与**映射文件**，映射里是**可还原的原始信息**
#   第 3 样最要紧：映射留在磁盘上，等于「脱敏了但原始内容还在」。
#
#   而「卸载说清了、但有个目录还在」是**不会自己喊出来**的失败，
#   所以这一条 e2e 必须**真的装一次、真的产生数据、真的卸载、真的看磁盘**。
#
# 【判据独立于被测实现】
#   磁盘状态用 shell 的 `test -e` / `ls` 读，**不复用** Java 侧的任何代码。
#
# 【退出码】
#   0  = 全部跑完且通过
#   1  = 有断言失败
#   77 = 环境不具备（这台机器上没有真模型包）——**不是通过**
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
MVN="$MVN_BIN -B -o -Dstyle.color=never"
JAR="$ROOT/target/desens-core.jar"
PY="${PY:-python3}"

. "$ROOT/tools/e2e-common.sh"

MODEL_SRC="${LITEMASK_MODEL_SRC:-$HOME/.litemask/models/ner}"
WORK="$(mktemp -d)"
pass=0; fail=0

ok()   { echo "PASS $1"; pass=$((pass+1)); }
bad()  { echo "FAIL $1"; fail=$((fail+1)); }
check(){ # $1=名称 $2=期望 $3=实际
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望 $2，实际 $3）"; fi
}
contains(){ # $1=名称 $2=文件 $3=应含的串
  if [ ! -f "$2" ]; then bad "$1（要查的文件不存在: $2）"; return; fi
  if grep -qF -- "$3" "$2"; then ok "$1"; else
    bad "$1（文件里找不到「$3」）"; sed -n '1,20p' "$2" | sed 's/^/    | /'
  fi
}
notcontains(){ # $1=名称 $2=文件 $3=不该出现的串
  if [ ! -f "$2" ]; then bad "$1（要查的文件不存在: $2）"; return; fi
  if grep -qF -- "$3" "$2"; then
    bad "$1（文件里不该有「$3」，实际有）"
  else ok "$1"; fi
}

echo "== 0. 前置检查 =="
if [ ! -d "$MODEL_SRC" ]; then
  echo "SKIP 这台机器上没有真模型包（${MODEL_SRC}），跑不了卸载场景。"
  exit 77
fi
echo "   真模型包 id: litemask-ner-zh-char-v3（源目录 ${MODEL_SRC}）"

echo ""
echo "== 1. 构建 fat jar =="
( cd "$ROOT" && $MVN -q -f "$ROOT/pom.xml" -DskipTests package ) > "$WORK/build.log" 2>&1
check "构建 fat jar" "0" "$?"
[ -f "$JAR" ] && ok "fat jar 在位" || { bad "fat jar 不在: $JAR"; exit 1; }

# ---------------------------------------------------------------------------
# 这一节用**独立的 user.home 与独立的 tmpdir**，
# 免得清掉这台机器上真的东西（这个脚本跑的是真卸载，不能手滑）。
# ---------------------------------------------------------------------------
UHOME="$WORK/用户主目录"
UTMP="$WORK/临时目录"
WS="$UTMP/litemask-workspace"
mkdir -p "$UHOME" "$UTMP"

uprod() { env "JAVA_TOOL_OPTIONS=-Duser.home=${UHOME} -Djava.io.tmpdir=${UTMP}" "$RUN_JAVA" "$@"; }

echo ""
echo "== 2. 造出「装过模型、加过信任锚、跑过任务」的样子 =="

# (a) 装一个模型包到隔离的 user.home
"$ROOT/tools/package-model.sh" > "$WORK/pkg.log" 2>&1
PKG_RC=$?
if [ $PKG_RC -ne 0 ]; then
  echo "   打包模型包失败（可能是环境不具备），跳过这一条 e2e"
  sed -n '1,20p' "$WORK/pkg.log" | sed 's/^/      | /'
  exit 77
fi
ZIP="$(ls -1 "$ROOT"/dist/*.zip 2>/dev/null | head -1)"
[ -f "$ZIP" ] && ok "造出了模型包 zip" || { bad "没造出模型包 zip"; exit 1; }

# 信任它：先加公钥（用上一轮签好的那把钥匙，签不了就现生成一把）
SIGN_KEY="$WORK/发布密钥.json"
"$ROOT/tools/model-signer.sh" keygen "$SIGN_KEY" > "$WORK/keygen.log" 2>&1
check "生成发布密钥" "0" "$?"
"$ROOT/tools/model-signer.sh" pubkey "$SIGN_KEY" "$WORK/发布公钥.pem" > /dev/null 2>&1
check "取出公钥" "0" "$?"

uprod -jar "$JAR" model trust add "$WORK/发布公钥.pem" > "$WORK/trust-add.out" 2>&1
check "model trust add 退出码 0" "0" "$?"
[ -f "$UHOME/.litemask/trust/anchors.json" ] && ok "信任锚文件已创建" \
  || bad "信任锚文件没创建: $UHOME/.litemask/trust/anchors.json"

# 装模型包（先签名，否则验签不过装不上）
"$ROOT/tools/model-signer.sh" sign "$ZIP" "$SIGN_KEY" > /dev/null 2>&1
check "给模型包签名" "0" "$?"
uprod -jar "$JAR" model install "$ZIP" > "$WORK/install.out" 2>&1
check "model install 退出码 0" "0" "$?"
[ -d "$UHOME/.litemask/models/ner" ] && ok "模型包装上了" \
  || bad "模型包没装上: $UHOME/.litemask/models/ner"

# (b) 造出工作区数据：脱敏产物 + 映射文件
mkdir -p "$WS/session-1"
printf '{\n  "slots": {\n    "1": "张伟明"\n  }\n}\n' > "$WS/session-1/卷宗.map.json"
printf '脱敏后的内容\n' > "$WS/session-1/卷宗.redacted.txt"
ok "造出了工作区数据（脱敏产物 + 映射文件）"

# (c) 顺便放一个**不属于本工具**的文件，验证卸载不会误伤
mkdir -p "$UHOME/用户的照片"
printf 'x' > "$UHOME/用户的照片/1.jpg"
printf '重要' > "$UHOME/我的合同.docx"
ok "造出了不属于本工具的用户文件"

echo ""
echo "== 3. --dry-run：只列不删 =="
uprod -jar "$JAR" uninstall --all --dry-run > "$WORK/dry.out" 2>&1
check "uninstall --dry-run 退出码 0" "0" "$?"
contains "dry-run 说清将要删什么" "$WORK/dry.out" "将要删除"
contains "dry-run 明说没真删" "$WORK/dry.out" "没有真的删"
[ -d "$UHOME/.litemask/models/ner" ] && ok "dry-run 之后模型包还在" || bad "dry-run 把模型包删了"
[ -d "$WS" ] && ok "dry-run 之后工作区还在" || bad "dry-run 把工作区删了"

echo ""
echo "== 4. 默认模式：只清应用数据，用户数据保留并说明 =="
uprod -jar "$JAR" uninstall > "$WORK/uninstall-app.out" 2>&1
check "uninstall 退出码 0" "0" "$?"
[ -d "$UHOME/.litemask/models/ner" ] && bad "默认模式应该删掉模型包" || ok "模型包被删掉了"
[ -f "$UHOME/.litemask/trust/anchors.json" ] && bad "默认模式应该删掉信任锚" || ok "信任锚被删掉了"
[ -f "$WS/session-1/卷宗.map.json" ] && ok "默认模式保留了映射文件" || bad "默认模式不该删映射文件"
contains "说明了保留了什么" "$WORK/uninstall-app.out" "保留"
contains "告诉了用户怎么一并清" "$WORK/uninstall-app.out" "--all"

echo ""
echo "== 5. --all：连用户数据一起清，映射文件必须真的没了 =="
uprod -jar "$JAR" uninstall --all > "$WORK/uninstall-all.out" 2>&1
check "uninstall --all 退出码 0" "0" "$?"
[ -f "$WS/session-1/卷宗.map.json" ] && bad "--all 之后映射文件应该没了" || ok "映射文件被删掉了"
[ -d "$WS" ] && bad "--all 之后工作区目录应该没了" || ok "工作区目录被删掉了"
# 逐条确认工作区里一个都不剩
LEFT="$(ls -A "$WS" 2>/dev/null | wc -l | tr -d ' ')"
check "工作区里一个条目都不剩" "0" "$LEFT"

echo ""
echo "== 6. 绝不误伤不属于本工具的东西 =="
[ -f "$UHOME/我的合同.docx" ] && ok "用户的文件没被删" || bad "用户的文件被删了"
[ -f "$UHOME/用户的照片/1.jpg" ] && ok "用户的照片没被删" || bad "用户的照片被删了"

echo ""
echo "== 7. 幂等：再卸一次不该报错 =="
uprod -jar "$JAR" uninstall --all > "$WORK/uninstall-again.out" 2>&1
check "第二次 uninstall 退出码 0" "0" "$?"

echo ""
echo "=== e2e-uninstall: pass=$pass fail=$fail ==="
[ "$fail" -eq 0 ] || exit 1
exit 0
