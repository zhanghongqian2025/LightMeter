#!/usr/bin/env bash
# 模型包独立安装包：端到端。
#
# 【这一块为什么单独立一个 e2e】
#   方案 B 把模型包从核心包里拆了出去（核心包 ≤90MiB 才是硬指标）。
#   拆出去的代价是：用户手上**没有**模型包是常态，而没有模型包时
#   中文人名与机构名**不会**被脱敏——那不是「少一个功能」，
#   是一份没脱干净的文档。所以模型包必须有一条不靠手工复制目录的安装路径，
#   而这条路径只有走一遍**真的 zip → 真的安装 → 真的脱敏**才算数。
#
# 【这一条 e2e 覆盖什么】
#   1. 打包：确定性 zip（打两次摘要一样）+ .sha256 旁挂文件 + 条目齐全
#   2. 安装到默认位置（隔离的 user.home）后，工具**真的**认它、脱敏**真的**生效
#   3. 负控制：改坏一个字节的包**必须装不上**，且**装不上不能毁掉原来那份**
#      ——没有这条负控制，「装失败会毁掉原来那份」这个断言永远不会被真正触发过
#
# 【判据独立于被测实现】
#   zip 的条目与摘要由 Python 的 zipfile/hashlib 独立读，
#   **不复用** Java 侧的读取代码；脱敏结果由「产物里有没有原名」判断，
#   不读 Java 内部状态。
#
# 【退出码】
#   0  = 全部跑完且通过
#   1  = 有断言失败
#   77 = 环境不具备（这台机器上没有真模型包）——**不是通过**，
#        用 automake 那套「skip」约定，就是为了让它在链条里不被当成绿。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
MVN="$MVN_BIN -B -o -Dstyle.color=never"
JAR="$ROOT/target/desens-core.jar"
PY="${PY:-python3}"

# 运行形态由 e2e-common 决定：这里**不能**设 LITEMASK_E2E_NER——
# 那一支走的是「--ner-model / 环境变量显式指定」，
# 而这一条 e2e 要测的是**装完之后自动找到**的那条路（~/.litemask/models/ner）。
# 装之前 user.home 是隔离的空目录 → 工具必须报「未启用」；
# 装之后同一个 home 里就有了 → 工具必须报「已启用」。
# 前后这一对是本脚本的主心骨。
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
  # **文件必须先存在**。文件不存在时 grep 返回 2，条件判假 → 报「找不到」，
  # 这还算能看出问题；真正阴的是 absent()：文件不存在时 grep 也返回 2，
  # 于是「不该出现这个串」被判成通过——**在空集上通过的断言等于没断言**。
  if [ ! -f "$2" ]; then bad "$1（要查的文件不存在: $2）"; return; fi
  if grep -qF -- "$3" "$2"; then ok "$1"; else
    bad "$1（文件里找不到「$3」）"; sed -n '1,20p' "$2" | sed 's/^/    | /'
  fi
}

# 从 `model list` 的输出里取出某一行的版本键。
#
# 【为什么不直接去目录里 ls 目录名】
#   目录名也是版本键，但那样验的就只是「磁盘上的目录名」，
#   而用户看到的是**命令输出里的那一份**。两者可能对不上（输出打错、键算错），
#   而那种错只有从输出里取值才验得到。这里按输出取。
extract_key() { # $1=文件 $2=行里的标记（如「当前生效」）
  "$PY" -c "
import re, sys
mark = sys.argv[2]
try:
    lines = open(sys.argv[1], encoding='utf-8')
except OSError:
    sys.exit(0)
for line in lines:
    if mark in line:
        m = re.search(r'([A-Za-z0-9._-]+)（模型 id', line)
        if m:
            print(m.group(1))
            break
" "$1" "$2"
}
absent(){ # $1=名称 $2=文件 $3=不该出现的串
  # 前提：文件必须存在。否则这条断言是在空集上跑的，恒真。
  if [ ! -f "$2" ]; then bad "$1（要查的文件不存在: $2）——在空集上判「不存在」是恒真的"; return; fi
  if grep -qF -- "$3" "$2"; then
    bad "$1（文件里不该出现「$3」，实际出现了）"
  else ok "$1"; fi
}
notcontains(){ # $1=名称 $2=文件 $3=不该出现的串
  # 同 absent：载体不存在时不能判「没出现」。
  if [ ! -f "$2" ]; then bad "$1（要查的文件不存在: $2）"; return; fi
  if grep -qF -- "$3" "$2"; then
    bad "$1（输出里不该出现「$3」，实际出现了）"
  else ok "$1"; fi
}

cleanup() {
  [ -n "${WORK:-}" ] && rm_rf "${WORK}"
  [ -n "${E2E_HOME:-}" ] && rm_rf "${E2E_HOME}"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
echo "== 0. 前置检查 =="
if [ ! -d "$MODEL_SRC" ] || [ ! -f "$MODEL_SRC/manifest.json" ]; then
  echo "SKIP 这台机器上没有真模型包（${MODEL_SRC}）——模型包安装这条路径这一轮没验"
  echo "     （装一个模型包再跑一次本脚本，才能拿到这一项的结论）"
  exit 77
fi
MODEL_ID="$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['id'])" \
  "$MODEL_SRC/manifest.json")"
echo "   真模型包 id: ${MODEL_ID}（源目录 ${MODEL_SRC}）"

# 前提断言：程序目录旁边**不能**有模型包。
#
# 为什么必须查：`NerModelRegistry` 的查找顺序里，「程序所在目录/models/ner」
# 排在「用户目录」**前面**。要是那儿有一个模型，工具在「装之前」就已经是启用状态，
# 后面「装之前未启用 → 装之后启用」这一对断言就测的是另一个东西了——
# 而它照样会绿。前提不成立时必须显式拦下来。
if [ -d "$ROOT/target/models/ner" ]; then
  echo "FAIL 前提不成立：$ROOT/target/models/ner 存在，" >&2
  echo "     工具会在装之前就找到它，「装之前未启用」这条断言就没有意义了" >&2
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
echo "== 2. 打包模型包（确定性 + 旁挂摘要）=="
DIST="$WORK/dist"
"$PY" "$ROOT/tools/package_model.py" pack --src "$MODEL_SRC" --out-dir "$DIST" > "$WORK/pack.log" 2>&1
if [ $? -ne 0 ]; then
  echo "FAIL 打包失败"; sed -n '1,20p' "$WORK/pack.log"; exit 1
fi
ZIP="$DIST/$MODEL_ID.zip"
SIDECAR="$DIST/$MODEL_ID.zip.sha256"
[ -f "$ZIP" ] && ok "产出了模型包 zip" || { bad "没产出模型包 zip"; exit 1; }
[ -f "$SIDECAR" ] && ok "产出了 .sha256 旁挂文件" || { bad "没产出 .sha256 旁挂文件"; exit 1; }

"$PY" "$ROOT/tools/package_model.py" check --sidecar "$SIDECAR" > /dev/null 2>&1
check "旁挂摘要与 zip 一致" "0" "$?"

# 条目：独立用 Python 读 zip（不复用 Java 侧任何读取代码）
ENTRIES="$("$PY" -c "
import zipfile,sys
z=zipfile.ZipFile(sys.argv[1])
print(','.join(i.filename for i in z.infolist()))
" "$ZIP")"
check "zip 里正好 3 个条目且顺序固定" "manifest.json,ner.onnx,vocab.txt" "$ENTRIES"

# 确定性：换个目录再打一次，摘要必须一样。
#
# 为什么值得单独测：模型包要独立分发，用户得能核对「我拿到的这份和公布的一致吗」。
# zip 默认会写 mtime、顺序看目录遍历顺序——那就永远对不上。
"$PY" "$ROOT/tools/package_model.py" pack --src "$MODEL_SRC" --out-dir "$WORK/dist2" > /dev/null 2>&1
"$PY" "$ROOT/tools/package_model.py" check --sidecar "$WORK/dist2/$MODEL_ID.zip.sha256" \
  --target "$ZIP" > /dev/null 2>&1
check "两次打包摘要相同（确定性成立）" "0" "$?"

# 包装脚本本身也是交付物，得一起验。
#
# 为什么不能只测它调用的那个 .py：`package-model.sh` 多做了一件要紧的事——
# 跑 **Java 侧的加载闸门**（模型必须真的能加载）。只测 .py 就漏掉了这一半，
# 而那一半恰恰是「别把一个加载不了的包发出去」的保证。
echo "-- 连包装脚本一起验（它是交付物，不能只测它调用的那个 .py）--"
"$ROOT/tools/package-model.sh" --src "$MODEL_SRC" --out-dir "$WORK/dist3" > "$WORK/pkgsh.log" 2>&1
check "tools/package-model.sh 跑通（打包 + 摘要核对 + Java 侧加载闸门）" "0" "$?"
contains "包装脚本跑出了模型包" "$WORK/pkgsh.log" "模型包 id"
contains "包装脚本过了 Java 侧加载闸门" "$WORK/pkgsh.log" "PASS 模型包能加载"

# ---------------------------------------------------------------------------
echo ""
echo "== 2.5 签名与信任锚：模型包必须由受信任的钥匙签过名 =="
#
# 【为什么这一节排在安装之前】
#   模型包是**独立分发**的产物。一个「自洽的坏包」（清单合法、模型合法、
#   但故意漏识别人名）能通过现有的**全部**校验——格式对、摘要对、模型能加载。
#   装上去之后工具照常跑、照常说「脱敏完成」，而人名就留在输出文件里了。
#   旁挂的 .sha256 挡不住这个：摘要和包一起换掉就过了。
#   签名挡得住——它把「来源」和「包」绑在一起，而私钥不在分发链路上。
#
# 【判据独立于被测实现】
#   签名由 tools/model-signer.sh（发布侧工具，**不进发行包**）产生，
#   验签由产品自己做。这里验的是「产品认不认」，不是「产品算得对不对」
#   ——后者由单元测试用 JDK 原生 crypto 当独立标尺覆盖。
SIGN_KEY="$WORK/发布密钥.json"
"$ROOT/tools/model-signer.sh" keygen "$SIGN_KEY" > "$WORK/keygen.log" 2>&1
check "生成发布密钥（keygen）" "0" "$?"
KEY_ID="$(grep -oE 'ed25519-[0-9a-f]{12}' "$WORK/keygen.log" | head -1)"
if [ -n "$KEY_ID" ]; then ok "密钥标识是派生的（${KEY_ID}）"
else bad "拿不到派生的密钥标识"; sed -n '1,20p' "$WORK/keygen.log"; exit 1; fi

"$ROOT/tools/model-signer.sh" pubkey "$SIGN_KEY" "$WORK/发布公钥.pem" > /dev/null 2>&1
check "从密钥文件里取出公钥" "0" "$?"

# 信任锚写进**隔离的** user.home（$E2E_HOME/.litemask/trust/anchors.json）。
# 生产路径不设覆盖属性，靠的就是 user.home 下的默认位置——
# 这里正好顺带证明默认位置在真实运行形态下是对的。
prod -jar "$JAR" model trust add "$WORK/发布公钥.pem" > "$WORK/trust-add.out" 2>&1
check "model trust add 退出码 0" "0" "$?"
contains "trust add 报出了派生的密钥标识" "$WORK/trust-add.out" "$KEY_ID"
contains "trust add 提醒了要用带外渠道核对来源" "$WORK/trust-add.out" "带外"

prod -jar "$JAR" model trust list > "$WORK/trust-list.out" 2>&1
check "model trust list 退出码 0" "0" "$?"
contains "trust list 列出了这把钥匙" "$WORK/trust-list.out" "$KEY_ID"

# 给模型包签名。签名文件就放在包旁边（<包名>.sig）。
"$ROOT/tools/model-signer.sh" sign "$ZIP" "$SIGN_KEY" > "$WORK/sign.log" 2>&1
check "给模型包签名" "0" "$?"
[ -f "$ZIP.sig" ] && ok "产出了旁挂签名文件" || { bad "没产出签名文件"; exit 1; }
contains "签名信封里的算法是 Ed25519" "$ZIP.sig" '"alg" : "Ed25519"'
contains "签名信封里的密钥标识与信任锚一致" "$ZIP.sig" "$KEY_ID"

# ---------------------------------------------------------------------------
echo ""
echo "== 3. 装之前：工具必须是「未启用」，且人名不会被脱敏 =="
SAMPLE="$WORK/样本.txt"
printf '兹委托%s与%s代理本案，联系电话%s。\n本案由%s受理。\n' \
  "张伟明" "李红娟" "13800138000" "北京某某律师事务所" > "$SAMPLE"

prod -jar "$JAR" model info > "$WORK/info-before.txt" 2>&1
check "装之前 model info 退出码是 3（未启用）" "3" "$?"
contains "装之前 model info 说未启用" "$WORK/info-before.txt" "中文人名/机构名识别：未启用"

prod -jar "$JAR" redact "$SAMPLE" > "$WORK/redact-before.out" 2>&1
check "装之前 redact 退出码 0" "0" "$?"
contains "装之前 redact 输出声明未启用" "$WORK/redact-before.out" "中文人名/机构名识别：未启用"
# 脱敏件的命名是 <基名>.redacted<后缀>，映射才是 <原文件名>.redacted.map.json。
# 两者不是同一个派生规则（一个有扩展名一个没有），这里用同一个口径。
BEFORE_OUT="$WORK/样本.redacted.txt"
[ -f "$BEFORE_OUT" ] && ok "装之前产出了脱敏件" || { bad "装之前没产出脱敏件"; exit 1; }
absent "装之前人名**没有**被脱敏（这才是「装之前」的形态）" "$BEFORE_OUT" "<人名1>"
contains "装之前原人名还在文件里" "$BEFORE_OUT" "张伟明"
contains "装之前手机号照常被脱敏（正则那部分与模型无关）" "$BEFORE_OUT" "<手机号1>"

# ---------------------------------------------------------------------------
echo ""
echo "== 4. 装到默认位置（隔离的 user.home）=="
prod -jar "$JAR" model install "$ZIP" > "$WORK/install.out" 2>&1
check "model install 退出码 0" "0" "$?"
contains "安装输出报出了模型 id" "$WORK/install.out" "$MODEL_ID"
# 装到默认位置 → 确实会被自动找到。这句话是对用户的承诺，必须兑现。
# 而且它不是「假设」出来的：工具在安装后**真的查了一遍**查找顺序才说的。
contains "装到默认位置时说「下一次运行就会自动用上」" "$WORK/install.out" "下一次运行就会自动用上"
contains "装到默认位置时说清会用哪一份" "$WORK/install.out" "这一份"

INSTALLED="$E2E_HOME/.litemask/models/ner"
check "装到了默认位置（user.home 下的 .litemask/models/ner）" "3" \
  "$(ls -1 "$INSTALLED" 2>/dev/null | wc -l | tr -d ' ')"
contains "装好的目录里有清单" "$INSTALLED/manifest.json" "formatVersion"

# 残留检查：暂存目录与备份目录都不能留下。
RESIDUE="$(ls -1d "$E2E_HOME/.litemask/models/"*安装中-* "$E2E_HOME/.litemask/models/"*旧-* 2>/dev/null | wc -l | tr -d ' ')"
check "没留下暂存/备份目录" "0" "$RESIDUE"

echo ""
echo "-- 装之后：工具必须认它 --"
prod -jar "$JAR" model info > "$WORK/info-after.txt" 2>&1
check "装之后 model info 退出码是 0（已启用）" "0" "$?"
contains "装之后 model info 说已启用" "$WORK/info-after.txt" "中文人名/机构名识别：已启用"
contains "装之后 model info 报出了模型 id" "$WORK/info-after.txt" "$MODEL_ID"
contains "装之后 model info 报的目录就是刚装的位置" "$WORK/info-after.txt" "$INSTALLED"

# 多余的参数必须被拒，不能静默忽略：
# 静默忽略的话，用户以为自己在查某个指定的模型包，
# 拿到的却是「工具实际会用的那个」——两边对不上却看不出来。
prod -jar "$JAR" model info --to "$INSTALLED" > "$WORK/info-extra.out" 2>&1
check "model info 拒绝 --to（退出码 2）" "2" "$?"
prod -jar "$JAR" model info 随便一个参数 > "$WORK/info-extra2.out" 2>&1
check "model info 拒绝多余参数（退出码 2）" "2" "$?"

echo ""
echo "-- 装之后：脱敏必须真的生效 --"
rm -f "$WORK/样本.redacted.txt"
prod -jar "$JAR" redact "$SAMPLE" > "$WORK/redact-after.out" 2>&1
check "装之后 redact 退出码 0" "0" "$?"
contains "装之后 redact 输出声明已启用" "$WORK/redact-after.out" "中文人名/机构名识别：已启用"
OUT_AFTER="$WORK/样本.redacted.txt"
[ -f "$OUT_AFTER" ] && ok "产出了脱敏件" || { bad "没产出脱敏件"; exit 1; }
contains "人名被脱敏成 <人名1>" "$OUT_AFTER" "<人名1>"
contains "第二个人名被脱敏成 <人名2>" "$OUT_AFTER" "<人名2>"
contains "机构名被脱敏成 <机构名1>" "$OUT_AFTER" "<机构名1>"
absent "产物里没有原人名" "$OUT_AFTER" "张伟明"
absent "产物里没有第二个人名" "$OUT_AFTER" "李红娟"
absent "产物里没有原机构名" "$OUT_AFTER" "北京某某律师事务所"
MAP="$WORK/样本.txt.redacted.map.json"
contains "映射里留着原文（能还原）" "$MAP" "张伟明"

# ---------------------------------------------------------------------------
echo ""
echo "== 5. 负控制：改坏一个字节的包必须装不上，而且不能毁掉原来那份 =="
BADZIP="$WORK/坏包.zip"
"$PY" "$ROOT/tools/package_model.py" tamper --input "$ZIP" --output "$BADZIP" \
  --entry ner.onnx > /dev/null 2>&1
[ -f "$BADZIP" ] && ok "造出了坏包" || { bad "没造出坏包"; exit 1; }

# 这个坏包**也要签名**。
#
# 为什么：验签是安装的**第一道**关卡，不签名的包根本走不到「清单里的摘要
# 和模型文件对不上」那一关。没签名的话，这条断言会改去测「有没有签名」，
# 而「装失败不能毁掉原来那份」这个真正要测的东西就被顺带测掉了。
# 签名之后，验签过、校验不过——失败原因正是「摘要」，这才是这一节要测的。
"$ROOT/tools/model-signer.sh" sign "$BADZIP" "$SIGN_KEY" > /dev/null 2>&1
check "给坏包也签上名（好让失败发生在校验那一关）" "0" "$?"

prod -jar "$JAR" model install "$BADZIP" > "$WORK/install-bad.out" 2>&1
BADRC=$?
if [ "$BADRC" -ne 0 ]; then ok "坏包安装以非零退出码失败（实际 ${BADRC}）"
else bad "坏包居然装成功了 —— 校验形同虚设"; fi
contains "失败原因说的是校验/摘要问题" "$WORK/install-bad.out" "摘要"

echo "-- 原来那份必须完好 --"
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
check "装失败之后 model info 仍是已启用" "0" "$?"
contains "装失败之后仍然是同一个模型 id" "$WORK/info-after-bad.txt" "$MODEL_ID"

rm -f "$WORK/样本.redacted.txt"
prod -jar "$JAR" redact "$SAMPLE" > "$WORK/redact-after-bad.out" 2>&1
check "装失败之后 redact 仍然退出码 0" "0" "$?"
absent "装失败之后人名**仍然**被脱敏（原来那份没被弄坏）" "$OUT_AFTER" "张伟明"

# 坏包也不能污染安装目录
check "装失败后安装目录仍是 3 个文件" "3" "$(ls -1 "$INSTALLED" 2>/dev/null | wc -l | tr -d ' ')"

# ---------------------------------------------------------------------------
echo ""
echo "== 5.5 验签负控制：不可信的包必须装不上 =="
#
# 【这一节为什么独立于上一节】
#   上一节测的是「包里坏了」（清单的摘要对不上模型文件）——
#   那是**内容**坏了。这一节测的是「来源不可信」：包本身可能完全自洽，
#   但没人能证明它是发布方发的。
#
#   两者挡不住的东西不一样：
#   内容坏了 → 校验层挡（摘要对不上）；
#   内容没坏但来源不可信 → 校验层**挡不住**（清单合法、摘要合法、模型能加载，
#   甚至可以故意漏识别人名），只有签名这一层挡得住。
#
# 【为什么要分别测四种坏法】
#   每一种坏法对应用户下一步的动作不同：
#   没签名文件 → 「去跟发布方要签名文件」；
#   钥匙不认   → 「去核对这把公钥是不是发布方的」；
#   签完被改   → 「这包在签名之后被动过」；
#   全都过了但没验签 → 用户明确说了「我知道来源不明，我就是要装」。
#   把它们混成一句「签名有问题」，用户就只能猜。
NEG_DIR="$WORK/负控制位置"

# (a) 根本没有签名文件
UNSIGNED="$WORK/未签名包.zip"
cp "$ZIP" "$UNSIGNED"
[ -f "$UNSIGNED.sig" ] && bad "前提不成立：未签名包旁边不该有 .sig" || ok "前提：未签名包旁边没有 .sig"
prod -jar "$JAR" model install "$UNSIGNED" --to "$NEG_DIR" > "$WORK/install-unsigned.out" 2>&1
if [ $? -ne 0 ]; then ok "没签名的包以非零退出码失败"
else bad "没签名的包居然装成功了 —— 验签形同虚设"; fi
contains "失败原因说的是缺签名文件" "$WORK/install-unsigned.out" "没有签名文件"
notcontains "失败原因不该把「没验签」说成「验签不过」" "$WORK/install-unsigned.out" "验签不过"

# (b) 签名在，但用的不是我们信任的那把钥匙
STRANGER_KEY="$WORK/别的钥匙.json"
"$ROOT/tools/model-signer.sh" keygen "$STRANGER_KEY" > /dev/null 2>&1
STRANGER_ZIP="$WORK/别的钥匙包.zip"
cp "$ZIP" "$STRANGER_ZIP"
"$ROOT/tools/model-signer.sh" sign "$STRANGER_ZIP" "$STRANGER_KEY" > /dev/null 2>&1
check "用另一把钥匙签了名" "0" "$?"
prod -jar "$JAR" model install "$STRANGER_ZIP" --to "$NEG_DIR" > "$WORK/install-stranger.out" 2>&1
if [ $? -ne 0 ]; then ok "不被信任的钥匙签的包以非零退出码失败"
else bad "不被信任的钥匙签的包居然装成功了 —— 信任锚形同虚设"; fi
contains "失败原因说的是这把钥匙不认" "$WORK/install-stranger.out" "不被信任"

# (c) 签完之后包被改了一个字节
TAMPERED="$WORK/签完被改的包.zip"
cp "$ZIP" "$TAMPERED"
"$ROOT/tools/model-signer.sh" sign "$TAMPERED" "$SIGN_KEY" > /dev/null 2>&1
"$PY" -c "
import sys
p = sys.argv[1]
b = bytearray(open(p,'rb').read())
b[len(b)//2] ^= 0xFF
open(p,'wb').write(bytes(b))
" "$TAMPERED"
prod -jar "$JAR" model install "$TAMPERED" --to "$NEG_DIR" > "$WORK/install-tampered.out" 2>&1
if [ $? -ne 0 ]; then ok "签完被改的包以非零退出码失败"
else bad "签完被改的包居然装成功了 —— 签名没盖住内容"; fi
contains "失败原因说的是签的是另一个包" "$WORK/install-tampered.out" "另一个包"

# (d) 逃生门：用户明确说「我知道来源不明，我要装」
FORCE_DIR="$WORK/强装位置"
prod -jar "$JAR" model install "$UNSIGNED" --to "$FORCE_DIR" --allow-unsigned \
  > "$WORK/install-force.out" 2>&1
check "--allow-unsigned 强装退出码 0" "0" "$?"
contains "强装时明确提醒「没有验签」" "$WORK/install-force.out" "没有验签"
contains "强装时提醒了漏脱敏的后果" "$WORK/install-force.out" "漏脱敏"
check "强装确实装上了" "3" "$(ls -1 "$FORCE_DIR" 2>/dev/null | wc -l | tr -d ' ')"

# (e) 上面这一串失败，一个都不许毁掉现在生效的那一份
prod -jar "$JAR" model info > "$WORK/info-after-trust-neg.txt" 2>&1
check "一串验签失败之后 model info 仍是已启用" "0" "$?"
contains "一串验签失败之后仍然是同一个模型 id" "$WORK/info-after-trust-neg.txt" "$MODEL_ID"
check "一串验签失败之后生效目录仍是 3 个文件" "3" \
  "$(ls -1 "$INSTALLED" 2>/dev/null | wc -l | tr -d ' ')"
rm -f "$WORK/样本.redacted.txt"
prod -jar "$JAR" redact "$SAMPLE" > /dev/null 2>&1
contains "一串验签失败之后人名仍然被脱敏" "$WORK/样本.redacted.txt" "<人名1>"

# ---------------------------------------------------------------------------
echo ""
echo "== 6. model verify：验一份目录能不能用 =="
prod -jar "$JAR" model verify "$MODEL_SRC" > "$WORK/verify-ok.out" 2>&1
check "model verify 对好包退出码 0" "0" "$?"
contains "model verify 报出了模型 id" "$WORK/verify-ok.out" "$MODEL_ID"

# 把坏包解开成一个目录，再拿它去 verify
"$PY" -c "
import zipfile,sys
zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])
" "$BADZIP" "$WORK/坏包目录" > /dev/null 2>&1
prod -jar "$JAR" model verify "$WORK/坏包目录" > "$WORK/verify-bad.out" 2>&1
if [ $? -ne 0 ]; then ok "model verify 对坏包以非零退出码失败"
else bad "model verify 对坏包居然说可用"; fi

# ---------------------------------------------------------------------------
echo ""
echo "== 7. --to 装到指定目录 =="
ALT="$WORK/另一个位置"
prod -jar "$JAR" model install "$ZIP" --to "$ALT" > "$WORK/install-alt.out" 2>&1
check "带 --to 安装退出码 0" "0" "$?"
check "指定目录里有 3 个文件" "3" "$(ls -1 "$ALT" 2>/dev/null | wc -l | tr -d ' ')"
contains "指定目录里的清单是对的" "$ALT/manifest.json" "$MODEL_ID"
# 面向用户的陈述不得超出实际核实的范围：
# --to 指定的目录**未必**在查找路径里，所以不能说「下一次运行就会自动用上」。
# 这时工具必须说清「实际会用哪一份」。
notcontains "带 --to 时不说「会自动用上」（那个目录不在默认查找位置里）" \
  "$WORK/install-alt.out" "下一次运行就会自动用上"
contains "带 --to 时说清实际会用哪一份" "$WORK/install-alt.out" "不是你刚装的这一份"
contains "带 --to 时告诉用户怎么才能用上" "$WORK/install-alt.out" "--ner-model"

# --to 装的那份，它的历史版本也放在那个目录旁边；
# 所以 list/rollback/remove 也必须认 --to，否则那份永远回退不了。
prod -jar "$JAR" model list --to "$ALT" > "$WORK/list-alt.out" 2>&1
check "model list --to 退出码 0" "0" "$?"
contains "model list --to 报的是指定目录" "$WORK/list-alt.out" "$ALT"

# ---------------------------------------------------------------------------
echo ""
echo "== 8. 版本共存：装第二份 → 旧的保留 → list → 回退 → 删 =="
#
# 【这一节为什么必须存在】
#   模型包是独立迭代的产物：新版本不一定比旧版本好。
#   而模型包一旦没了，**中文人名与机构名就不会被脱敏**——
#   那不是「少一个功能」，是一份没脱干净的文档。
#   所以「装新版本」必须可逆：旧的要留着，要能退回去。
#
# 【判据独立于被测实现】
#   版本键是从 `model list` 的**输出**里取的（不是去目录里 ls 目录名）——
#   用户看到的是输出，验的也应该是输出。
#   脱敏结果仍然只看产物里有没有原名。

# 造第二份「能区分开的」模型包：同一份模型，只改清单里的 id。
# 模型还是那个真模型（所以「回退后还能正常脱敏」测的仍然是真模型），
# 但两版的版本键不同、能被区分开。
V4_ID="litemask-ner-zh-char-v4"
"$PY" "$ROOT/tools/package_model.py" pack --src "$MODEL_SRC" --out-dir "$WORK/dist-v4" \
  --id "$V4_ID" > "$WORK/pack-v4.log" 2>&1
if [ $? -ne 0 ]; then echo "FAIL 造第二份模型包失败"; sed -n '1,20p' "$WORK/pack-v4.log"; exit 1; fi
ZIP2="$WORK/dist-v4/$V4_ID.zip"
[ -f "$ZIP2" ] && ok "造出了第二份模型包" || { bad "没造出第二份模型包"; exit 1; }
# 第二份也要签名：装模型包的第一道关卡是验签，
# 不签名的话这一节测的会是「签名在不在」，而不是「版本共存」。
"$ROOT/tools/model-signer.sh" sign "$ZIP2" "$SIGN_KEY" > /dev/null 2>&1
check "给第二份模型包签名" "0" "$?"

# 装第二份：当前生效的换成 v4，v3 进历史
prod -jar "$JAR" model install "$ZIP2" > "$WORK/install-v4.out" 2>&1
check "装第二份退出码 0" "0" "$?"
contains "装第二份的输出报出了新版本 id" "$WORK/install-v4.out" "$V4_ID"
contains "装第二份时说旧版本被保留下来了" "$WORK/install-v4.out" "已保留原来的版本"
# 建议给用户的命令必须用**这台机器上真的存在**的调用方式。
# 这里 e2e 自己是用 `java -jar <jar>` 调的，所以工具建议的应当也是这一串——
# 照着敲就能跑。以前这里写死了一个不存在的程序名，用户照着敲就是 command not found。
contains "装第二份时建议的回退命令用的是本工具自己的调用方式" "$WORK/install-v4.out" "java -jar ${JAR}"
notcontains "建议里不该出现那个这台机器上并不存在的程序名" "$WORK/install-v4.out" "litemask model"

prod -jar "$JAR" model info > "$WORK/info-v4.txt" 2>&1
check "装第二份后 model info 退出码 0" "0" "$?"
contains "装第二份后生效的是 v4" "$WORK/info-v4.txt" "$V4_ID"

# 旧版本必须真的还在磁盘上（能回退的前提）
HIST_DIR="$E2E_HOME/.litemask/models/ner.versions"
[ -d "$HIST_DIR" ] && ok "历史版本目录建出来了" || { bad "没有历史版本目录"; exit 1; }
HIST_COUNT="$(ls -1 "$HIST_DIR" 2>/dev/null | wc -l | tr -d ' ')"
check "历史里留着 1 个版本" "1" "$HIST_COUNT"

echo ""
echo "-- model list：当前 + 历史都要列出来 --"
prod -jar "$JAR" model list > "$WORK/list.txt" 2>&1
check "model list 退出码 0" "0" "$?"
contains "model list 标出了当前生效的那份" "$WORK/list.txt" "当前生效"
contains "model list 标出了保留的历史版本" "$WORK/list.txt" "保留的历史版本"
contains "model list 里看得到 v3" "$WORK/list.txt" "$MODEL_ID"
contains "model list 里看得到 v4" "$WORK/list.txt" "$V4_ID"
# 建议给用户的命令同样要用本工具自己的调用方式。
contains "model list 的建议用的是本工具自己的调用方式" "$WORK/list.txt" "java -jar ${JAR} model rollback"
notcontains "model list 里不该出现那个这台机器上并不存在的程序名" "$WORK/list.txt" "litemask model"

CURRENT_KEY="$(extract_key "$WORK/list.txt" "当前生效")"
HIST_KEY="$(extract_key "$WORK/list.txt" "保留的历史版本")"
if [ -n "$CURRENT_KEY" ]; then ok "从 list 输出里取到了当前版本的键"
else bad "从 list 输出里取不到当前版本的键"; exit 1; fi
if [ -n "$HIST_KEY" ]; then ok "从 list 输出里取到了历史版本的键"
else bad "从 list 输出里取不到历史版本的键"; exit 1; fi

echo ""
echo "-- 回退：换回旧版本 --"
prod -jar "$JAR" model rollback > "$WORK/rollback.out" 2>&1
check "model rollback（不带参数）退出码 0" "0" "$?"
contains "回退输出报出了版本 id" "$WORK/rollback.out" "$MODEL_ID"
prod -jar "$JAR" model info > "$WORK/info-rollback.txt" 2>&1
check "回退后 model info 退出码 0" "0" "$?"
contains "回退后生效的是 v3" "$WORK/info-rollback.txt" "$MODEL_ID"

# 回退后那份必须是**真能用**的：脱敏还得照常做。
# 只看「目录在不在」是不够的——那测的是文件系统的搬运，不是模型能不能用。
rm -f "$WORK/样本.redacted.txt"
prod -jar "$JAR" redact "$SAMPLE" > "$WORK/redact-rollback.out" 2>&1
check "回退后 redact 退出码 0" "0" "$?"
contains "回退后人名仍然被脱敏（回退到的那份是真能用的）" "$WORK/样本.redacted.txt" "<人名1>"
absent "回退后产物里没有原人名" "$WORK/样本.redacted.txt" "张伟明"

echo ""
echo "-- 按版本键回退到指定版本 --"
prod -jar "$JAR" model rollback "$CURRENT_KEY" > "$WORK/rollback-v4.out" 2>&1
check "按版本键回退退出码 0" "0" "$?"
prod -jar "$JAR" model info > "$WORK/info-forward.txt" 2>&1
contains "按版本键回退后生效的是 v4" "$WORK/info-forward.txt" "$V4_ID"

echo ""
echo "-- 负控制：对不上的版本名必须明确失败，而且不能顺手改掉当前版本 --"
prod -jar "$JAR" model rollback "根本没有这个版本" > "$WORK/rollback-bad.out" 2>&1
if [ $? -ne 0 ]; then ok "回退到不存在的版本以非零退出码失败"
else bad "回退到不存在的版本居然成功了"; fi
prod -jar "$JAR" model info > "$WORK/info-after-bad-rollback.txt" 2>&1
contains "回退失败之后当前版本没变" "$WORK/info-after-bad-rollback.txt" "$V4_ID"

prod -jar "$JAR" model remove "根本没有这个版本" > "$WORK/remove-bad.out" 2>&1
if [ $? -ne 0 ]; then ok "删除不存在的版本以非零退出码失败"
else bad "删除不存在的版本居然成功了"; fi

# 路径穿越：版本名里带 ../ 必须被拒，而且**不能删掉别的东西**。
# 探针放在「真按 ../ 拼路径会落到的地方」——放在别处的话，
# 即使代码有洞，这条断言也照样绿（等于在空集上判「没删掉」）。
CANARY="$E2E_HOME/.litemask/不能被删掉的东西"
printf '还在\n' > "$CANARY"
prod -jar "$JAR" model remove "../../不能被删掉的东西" > "$WORK/remove-traversal.out" 2>&1
if [ $? -ne 0 ]; then ok "带路径分隔符的版本名被拒（退出码非零）"
else bad "带路径分隔符的版本名居然被接受了"; fi
[ -f "$CANARY" ] && ok "路径穿越没有删掉任何东西" || bad "路径穿越把不该删的东西删了"

echo ""
echo "-- 删掉历史版本：当前生效的那份不受影响 --"
prod -jar "$JAR" model remove "$HIST_KEY" > "$WORK/remove-hist.out" 2>&1
check "删除历史版本退出码 0" "0" "$?"
prod -jar "$JAR" model list > "$WORK/list2.txt" 2>&1
absent "list 里不再有 v3" "$WORK/list2.txt" "$MODEL_ID"
contains "list 里还有 v4" "$WORK/list2.txt" "$V4_ID"
prod -jar "$JAR" model info > "$WORK/info-final.txt" 2>&1
check "删掉历史版本后 model info 退出码 0" "0" "$?"
contains "删掉历史版本后当前生效的没变" "$WORK/info-final.txt" "$V4_ID"

# 历史目录里不能留下暂存残留。
# 用 find 而不是 `ls -d "$DIR/".*`：后者把 `.` 与 `..` 也数进去，
# 于是这条断言恒为「有残留」——判据自己坏了，却看起来像被测代码坏了。
RESIDUE2="$(find "$HIST_DIR" -mindepth 1 -maxdepth 1 -name '.*' 2>/dev/null | wc -l | tr -d ' ')"
check "历史目录里没留下暂存残留" "0" "$RESIDUE2"

echo ""
echo "== 9. model trust remove：不再信任一把钥匙 =="
#
# 放在最后：删掉信任锚之后，那台机器就装不了这份模型包了，
# 放中间会把后面几节一起带崩。
prod -jar "$JAR" model trust remove "$KEY_ID" > "$WORK/trust-remove.out" 2>&1
check "model trust remove 退出码 0" "0" "$?"
contains "trust remove 说清已经不再信任" "$WORK/trust-remove.out" "已不再信任"
prod -jar "$JAR" model trust list > "$WORK/trust-list-after.txt" 2>&1
notcontains "trust list 里已经没有这把钥匙了" "$WORK/trust-list-after.txt" "$KEY_ID"
prod -jar "$JAR" model trust remove "$KEY_ID" > "$WORK/trust-remove-again.out" 2>&1
check "删一把已经删过的钥匙仍然是退出码 0" "0" "$?"
contains "删一把已经删过的钥匙要说「本来就没有」" "$WORK/trust-remove-again.out" "本来就没有"

echo ""
echo "-- 负控制：同一把钥匙既在内置锚里又在用户级 --"
#
# 【为什么这个场景必须单独测】
#   `model trust remove` 只能删**用户级**那一份。
#   同一把公钥若还在**内置**锚里，它照样受信——
#   这时候工具若说「已不再信任」，那是**一句不实的陈述**：
#   用户会以为模型包再也装不上了，其实照样装得上。
#
#   这个场景在真实使用里很常见：发布方把公钥内置了，
#   用户又从官网拿到一份加了一遍。
#
# 【用独立的 user.home】
#   这一节会删掉用户级那份公钥。主流程后面还要用这把钥匙装模型包，
#   所以这里换一个隔离的 home，不动主流程的状态。
ALT_HOME="$WORK/备用home"
mkdir -p "$ALT_HOME"
BUILTIN_ANCHORS="$WORK/内置锚.json"
"$PY" -c "
import base64, json, sys
# 公钥的 Base64（SPKI DER）从公钥 PEM 里取。
pem = open(sys.argv[1], encoding='utf-8').read()
body = ''.join(l for l in pem.splitlines() if l and not l.startswith('-----'))
der = base64.b64decode(body)
json.dump({'anchors': [{'publicKey': base64.b64encode(der).decode()}]},
          open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False)
" "$WORK/发布公钥.pem" "$BUILTIN_ANCHORS"
[ -f "$BUILTIN_ANCHORS" ] && ok "造出了内置信任锚文件" || { bad "没造出内置信任锚文件"; exit 1; }

alt_prod() { # 额外指定内置信任锚，且用独立的 user.home
  env "JAVA_TOOL_OPTIONS=-Duser.home=${ALT_HOME} -Dlitemask.trust.builtin=${BUILTIN_ANCHORS}" \
    "$RUN_JAVA" "$@"
}
alt_prod -jar "$JAR" model trust add "$WORK/发布公钥.pem" > "$WORK/alt-trust-add.out" 2>&1
check "在备用 home 里也加上了这把公钥" "0" "$?"
alt_prod -jar "$JAR" model trust list > "$WORK/alt-trust-list.out" 2>&1
check "备用 home 的 trust list 退出码 0" "0" "$?"
contains "同一把钥匙只算一把（去重后是 1 把）" "$WORK/alt-trust-list.out" "已信任 1 把公钥"
contains "两处都有时来源标成内置" "$WORK/alt-trust-list.out" "（内置）"

alt_prod -jar "$JAR" model trust remove "$KEY_ID" > "$WORK/alt-trust-remove.out" 2>&1
check "备用 home 的 trust remove 退出码 0" "0" "$?"
notcontains "它还在内置锚里，不能说「已不再信任」" "$WORK/alt-trust-remove.out" "已不再信任"
contains "必须说清它仍在内置信任锚里" "$WORK/alt-trust-remove.out" "内置"
alt_prod -jar "$JAR" model trust list > "$WORK/alt-trust-list2.out" 2>&1
contains "删掉用户级那把之后，内置那把还在（仍然受信）" "$WORK/alt-trust-list2.out" "$KEY_ID"

echo ""
echo "=== e2e-model: pass=$pass fail=$fail ==="
[ "$fail" -eq 0 ] || exit 1
exit 0
