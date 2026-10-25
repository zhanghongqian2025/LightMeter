#!/usr/bin/env bash
# 模型包**下载**路径：端到端。
#
# 【这一块为什么单独立一个 e2e】
#   方案 B 把模型包从核心包里拆了出去，「用户手上没有模型包」是常态，
#   而没有模型包时中文人名与机构名**不会**被脱敏。
#   块 M3 把「怎么装」做完了（model install），但「怎么到用户手里」没做——
#   模型包只能靠人手递一个 zip 过去。
#   这一条 e2e 走的就是补上的那条路：**给一个地址，工具自己取、自己验、自己装**。
#
# 【这一条 e2e 覆盖什么】
#   1. 真链路：本地 HTTP 服务 → model download → 真的装上 → 真的脱敏生效
#   2. 三条负控制（每一条都要能让「装上」这件事不成立）：
#      (a) 包被改过（签名是真的，但内容对不上清单）→ 装不上
#      (b) 签名用的钥匙不受信 → 装不上
#      (c) 签名文件根本取不到 → 下载阶段就失败
#   3. 负控制之后：原来那份模型包必须完好，人名仍然被脱敏
#   4. 临时下载目录不留残留
#
# 【判据独立于被测实现】
#   zip 的条目与摘要由 Python 的 zipfile/hashlib 独立读，
#   **不复用** Java 侧的读取代码；脱敏结果由「产物里有没有原名」判断。
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

# 运行形态与 e2e-model 一样：走「装完之后自动找到」的那条路（~/.litemask/models/ner）。
. "$ROOT/tools/e2e-common.sh"

MODEL_SRC="${LITEMASK_MODEL_SRC:-$HOME/.litemask/models/ner}"
WORK="$(mktemp -d)"
pass=0; fail=0
HTTP_PID=""

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
absent(){ # $1=名称 $2=文件 $3=不该出现的串
  # 前提：文件必须存在。否则这条断言是在空集上跑的，恒真。
  if [ ! -f "$2" ]; then bad "$1（要查的文件不存在: $2）——在空集上判「不存在」是恒真的"; return; fi
  if grep -qF -- "$3" "$2"; then
    bad "$1（文件里不该出现「$3」，实际出现了）"
    sed -n '1,20p' "$2" | sed 's/^/    | /'
  else ok "$1"; fi
}

cleanup() {
  if [ -n "$HTTP_PID" ]; then
    kill "$HTTP_PID" 2>/dev/null || true
    wait "$HTTP_PID" 2>/dev/null || true
  fi
  rm_rf "$WORK"
}
trap cleanup EXIT

if [ ! -d "$MODEL_SRC" ]; then
  echo "SKIP 这台机器上没有真模型包（${MODEL_SRC}）——模型包下载这条路径这一轮没验"
  echo "     （装一个模型包再跑一次本脚本，才能拿到这一项的结论）"
  exit 77
fi
MODEL_ID="$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['id'])" \
  "$MODEL_SRC/manifest.json")"
echo "   真模型包 id: ${MODEL_ID}（源目录 ${MODEL_SRC}）"

# 前提断言：程序目录旁边**不能**有模型包。
# 与 e2e-model 同一条理由：查找顺序里「程序所在目录/models/ner」排在前面，
# 要是那儿有一个模型，「装之前未启用」这条断言就测的是另一个东西了。
if [ -d "$ROOT/target/models/ner" ]; then
  echo "FAIL 前提不成立：$ROOT/target/models/ner 存在，工具会在装之前就找到它" >&2
  exit 1
fi
ok "前提：程序目录旁边没有模型包（装之前必然是未启用形态）"

echo ""
echo "== 1. 构建 fat jar =="
$MVN -q -f "$ROOT/pom.xml" -DskipTests package > /dev/null 2>&1 || { echo "打包失败"; exit 1; }
[ -f "$JAR" ] || { echo "没有产出 ${JAR}，实验无效"; exit 1; }
echo "   $(wc -c < "$JAR" | tr -d ' ') 字节"

# ---------------------------------------------------------------------------
echo ""
echo "== 2. 造一份可分发的模型包（zip + 签名）=="
SERVE="$WORK/服务目录"
DIST="$SERVE/dist"
mkdir -p "$DIST"
"$PY" "$ROOT/tools/package_model.py" pack --src "$MODEL_SRC" --out-dir "$DIST" > "$WORK/pack.log" 2>&1
if [ $? -ne 0 ]; then
  echo "FAIL 打包失败"; sed -n '1,20p' "$WORK/pack.log"; exit 1
fi
ZIP="$DIST/$MODEL_ID.zip"
[ -f "$ZIP" ] && ok "产出了模型包 zip" || { bad "没产出模型包 zip"; exit 1; }

# 签名：签名由 tools/model-signer.sh（发布侧工具，**不进发行包**）产生，
# 验签由产品自己做。这里验的是「产品认不认」，不是「产品算得对不对」。
SIGN_KEY="$WORK/发布密钥.json"
"$ROOT/tools/model-signer.sh" keygen "$SIGN_KEY" > "$WORK/keygen.log" 2>&1
check "生成发布密钥（keygen）" "0" "$?"
KEY_ID="$(grep -oE 'ed25519-[0-9a-f]{12}' "$WORK/keygen.log" | head -1)"
if [ -n "$KEY_ID" ]; then ok "密钥标识是派生的（${KEY_ID}）"
else bad "拿不到派生的密钥标识"; sed -n '1,20p' "$WORK/keygen.log"; exit 1; fi

"$ROOT/tools/model-signer.sh" pubkey "$SIGN_KEY" "$WORK/发布公钥.pem" > /dev/null 2>&1
check "从密钥文件里取出公钥" "0" "$?"

# 信任锚写进**隔离的** user.home（$E2E_HOME/.litemask/trust/anchors.json）。
prod -jar "$JAR" model trust add "$WORK/发布公钥.pem" > "$WORK/trust-add.out" 2>&1
check "model trust add 退出码 0" "0" "$?"
contains "trust add 报出了派生的密钥标识" "$WORK/trust-add.out" "$KEY_ID"

"$ROOT/tools/model-signer.sh" sign "$ZIP" "$SIGN_KEY" > "$WORK/sign.log" 2>&1
check "给模型包签名" "0" "$?"
[ -f "$ZIP.sig" ] && ok "产出了旁挂签名文件" || { bad "没产出签名文件"; exit 1; }
contains "签名信封里的算法是 Ed25519" "$ZIP.sig" '"alg" : "Ed25519"'
contains "签名信封里的密钥标识与信任锚一致" "$ZIP.sig" "$KEY_ID"

# ---------------------------------------------------------------------------
echo ""
echo "== 3. 起一个本地 HTTP 服务（模拟发布方的下载点）=="
# -u 必须加：python 的 stdout 重定向到文件时是**块缓冲**的，
# 不加的话「Serving HTTP on ... port N」那行会卡在缓冲区里，
# 于是脚本读不到端口号，只能判「服务没起来」——而服务其实好好的。
"$PY" -u -m http.server 0 --bind 127.0.0.1 --directory "$SERVE" > "$WORK/http.log" 2>&1 &
HTTP_PID=$!
PORT=""
i=0
while [ $i -lt 100 ]; do
  PORT="$(grep -oE 'port [0-9]+' "$WORK/http.log" 2>/dev/null | head -1 | grep -oE '[0-9]+')"
  [ -n "$PORT" ] && break
  i=$((i+1)); sleep 0.1
done
if [ -z "$PORT" ]; then
  echo "FAIL 本地 HTTP 服务没起来"; sed -n '1,20p' "$WORK/http.log"; exit 1
fi
BASE="http://127.0.0.1:${PORT}"
ok "本地 HTTP 服务起来了（${BASE}）"

# ---------------------------------------------------------------------------
echo ""
echo "== 4. 下载之前：工具必须是「未启用」=="
SAMPLE="$WORK/样本.txt"
printf '兹委托%s与%s代理本案，联系电话%s。\n本案由%s受理。\n' \
  "张伟明" "李红娟" "13800138000" "北京某某律师事务所" > "$SAMPLE"

prod -jar "$JAR" model info > "$WORK/info-before.txt" 2>&1
check "下载之前 model info 退出码是 3（未启用）" "3" "$?"
contains "下载之前 model info 说未启用" "$WORK/info-before.txt" "中文人名/机构名识别：未启用"

# 临时下载目录的残留基线：下载命令用的是 java.io.tmpdir 下的
# litemask-model-download-* 目录，装完必须删掉。
TMP_BASE="${TMPDIR:-/tmp}"
TMP_BASE="${TMP_BASE%/}"
BEFORE_TMP="$(ls -1d "${TMP_BASE}"/litemask-model-download-* 2>/dev/null | wc -l | tr -d ' ')"

# ---------------------------------------------------------------------------
echo ""
echo "== 5. 真链路：下载 → 验签 → 装上 → 真的脱敏生效 =="
prod -jar "$JAR" model download "${BASE}/dist/${MODEL_ID}.zip" > "$WORK/download.out" 2>&1
check "model download 退出码 0" "0" "$?"
contains "下载输出报出了收了多少字节" "$WORK/download.out" "已下载模型包"
contains "下载输出报出了装好的模型 id" "$WORK/download.out" "$MODEL_ID"
contains "装到默认位置时说「下一次运行就会自动用上」" "$WORK/download.out" "下一次运行就会自动用上"

INSTALLED="$E2E_HOME/.litemask/models/ner"
check "装到了默认位置（user.home 下的 .litemask/models/ner）" "3" \
  "$(ls -1 "$INSTALLED" 2>/dev/null | wc -l | tr -d ' ')"
contains "装好的目录里有清单" "$INSTALLED/manifest.json" "formatVersion"

echo "-- 装之后：工具必须认它 --"
prod -jar "$JAR" model info > "$WORK/info-after.txt" 2>&1
check "装之后 model info 退出码是 0（已启用）" "0" "$?"
contains "装之后 model info 说已启用" "$WORK/info-after.txt" "中文人名/机构名识别：已启用"
contains "装之后 model info 报出了模型 id" "$WORK/info-after.txt" "$MODEL_ID"

echo "-- 装之后：脱敏必须真的生效 --"
rm -f "$WORK/样本.redacted.txt"
prod -jar "$JAR" redact "$SAMPLE" > "$WORK/redact-after.out" 2>&1
check "装之后 redact 退出码 0" "0" "$?"
contains "装之后 redact 输出声明已启用" "$WORK/redact-after.out" "中文人名/机构名识别：已启用"
OUT_AFTER="$WORK/样本.redacted.txt"
[ -f "$OUT_AFTER" ] && ok "产出了脱敏件" || { bad "没产出脱敏件"; exit 1; }
contains "人名被脱敏成 <人名1>" "$OUT_AFTER" "<人名1>"
contains "机构名被脱敏成 <机构名1>" "$OUT_AFTER" "<机构名1>"
absent "产物里没有原人名" "$OUT_AFTER" "张伟明"
absent "产物里没有原机构名" "$OUT_AFTER" "北京某某律师事务所"

# 临时目录不留残留
AFTER_TMP="$(ls -1d "${TMP_BASE}"/litemask-model-download-* 2>/dev/null | wc -l | tr -d ' ')"
check "下载用的临时目录没留下残留" "$BEFORE_TMP" "$AFTER_TMP"

# ---------------------------------------------------------------------------
echo ""
echo "== 6. 负控制(a)：包被改过（签名是真的，内容对不上清单）必须装不上 =="
#
# 为什么这个坏包**也要签名**：验签是安装的第一道关卡，不签名的包根本走不到
# 「清单里的摘要和模型文件对不上」那一关。签名之后，验签过、校验不过——
# 失败原因正是「摘要」，这才是这一节要测的。
BADZIP="$DIST/坏包.zip"
"$PY" "$ROOT/tools/package_model.py" tamper --input "$ZIP" --output "$BADZIP" \
  --entry ner.onnx > /dev/null 2>&1
[ -f "$BADZIP" ] && ok "造出了坏包" || { bad "没造出坏包"; exit 1; }
"$ROOT/tools/model-signer.sh" sign "$BADZIP" "$SIGN_KEY" > /dev/null 2>&1
check "给坏包也签上名（好让失败发生在校验那一关）" "0" "$?"

prod -jar "$JAR" model download "${BASE}/dist/坏包.zip" > "$WORK/download-bad.out" 2>&1
BADRC=$?
if [ "$BADRC" -ne 0 ]; then ok "坏包下载+安装以非零退出码失败（实际 ${BADRC}）"
else bad "坏包居然装成功了 —— 校验形同虚设"; fi
contains "失败原因说的是校验/摘要问题" "$WORK/download-bad.out" "摘要"

# ---------------------------------------------------------------------------
echo ""
echo "== 7. 负控制(b)：签名用的钥匙不受信，必须装不上 =="
STRANGER_KEY="$WORK/陌生密钥.json"
"$ROOT/tools/model-signer.sh" keygen "$STRANGER_KEY" > /dev/null 2>&1
"$ROOT/tools/model-signer.sh" sign "$ZIP" "$STRANGER_KEY" > /dev/null 2>&1
check "用另一把钥匙签了名" "0" "$?"

prod -jar "$JAR" model download "${BASE}/dist/${MODEL_ID}.zip" > "$WORK/download-stranger.out" 2>&1
STRRC=$?
if [ "$STRRC" -ne 0 ]; then ok "不受信的钥匙以非零退出码失败（实际 ${STRRC}）"
else bad "不受信的钥匙居然装成功了 —— 验签形同虚设"; fi
contains "失败原因说的是这把钥匙不认" "$WORK/download-stranger.out" "不被信任"

# ---------------------------------------------------------------------------
echo ""
echo "== 8. 负控制(c)：签名文件取不到，下载阶段就该失败 =="
NOSIG="$DIST/没签名的.zip"
cp "$ZIP" "$NOSIG"
prod -jar "$JAR" model download "${BASE}/dist/没签名的.zip" > "$WORK/download-nosig.out" 2>&1
NOSIGRC=$?
if [ "$NOSIGRC" -ne 0 ]; then ok "取不到签名以非零退出码失败（实际 ${NOSIGRC}）"
else bad "取不到签名居然成功了 —— 签名这一步形同虚设"; fi
contains "失败原因说的是签名文件取不到" "$WORK/download-nosig.out" "下载失败"

# ---------------------------------------------------------------------------
echo ""
echo "== 9. 负控制(d)：download 不接受 --allow-unsigned =="
prod -jar "$JAR" model download "${BASE}/dist/${MODEL_ID}.zip" --allow-unsigned > "$WORK/download-unsigned.out" 2>&1
check "download 拒绝 --allow-unsigned（退出码 2）" "2" "$?"
contains "错误信息点名了这个参数" "$WORK/download-unsigned.out" "--allow-unsigned"

# ---------------------------------------------------------------------------
echo ""
echo "== 10. 一串失败之后：原来那份模型包必须完好，人名仍然被脱敏 =="
check "装好的目录仍有 3 个文件" "3" "$(ls -1 "$INSTALLED" 2>/dev/null | wc -l | tr -d ' ')"
contains "清单没被动过" "$INSTALLED/manifest.json" "formatVersion"
"$PY" -c "
import hashlib,sys
h=hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()
m=sys.argv[2]
print('OK' if h==m else 'BAD')
" "$INSTALLED/ner.onnx" "$("$PY" -c "
import json,sys
print(json.load(open(sys.argv[1],encoding='utf-8'))['files']['model']['sha256'])
" "$INSTALLED/manifest.json")" > "$WORK/hash-check.txt" 2>&1
check "装好的模型文件摘要仍与清单一致（没被坏包污染）" "OK" "$(cat "$WORK/hash-check.txt")"

prod -jar "$JAR" model info > "$WORK/info-after-bad.txt" 2>&1
check "一串失败之后 model info 仍是已启用" "0" "$?"
contains "一串失败之后仍然是同一个模型 id" "$WORK/info-after-bad.txt" "$MODEL_ID"

rm -f "$WORK/样本.redacted.txt"
prod -jar "$JAR" redact "$SAMPLE" > "$WORK/redact-after-bad.out" 2>&1
check "一串失败之后 redact 仍然退出码 0" "0" "$?"
absent "一串失败之后人名**仍然**被脱敏（原来那份没被弄坏）" "$OUT_AFTER" "张伟明"

echo ""
echo "=== e2e-download: pass=${pass} fail=${fail} ==="
[ "$fail" -eq 0 ] || exit 1
exit 0
