#!/usr/bin/env bash
# 映射件的加密封装与权限收紧：端到端。
#
# 【这一块为什么单独立一个 e2e】
#   映射件是本工具**唯一含原文**的产物。脱敏件里只有令牌，原值全在映射里。
#   这就带来一个很尖锐的处境：
#     「脱敏件可以发出去」是本工具的核心承诺，
#     而「别把映射件也发出去」只是一个**约定**——而这两份文件通常在同一个目录里。
#   原来的映射件是**明文 JSON**、权限默认 0644：
#     · 同机其他用户能直接读到原值；
#     · 「只是换了个编码」（base64）就能还原，挡住它没有任何意义。
#   → 这一条 e2e 要验的是**性质**：「文件里没有原值」，
#     而不是「产品自己说它加密了」。
#
# 【判据独立于被测实现】
#   · 「文件里没有原值」由 tools/verify_sealed_map.py 判：
#     纯 Python 标准库，不 import 任何产品代码。
#   · 还原结果由 diff 逐字比。
#   · 权限由 stat 读。
#   全部都不复用产品侧的任何代码。
#
# 【退出码】
#   0  = 全部跑完且通过
#   1  = 有断言失败
#   77 = 环境不具备（这台机器上构建不出 jar）——**不是通过**
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
if [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "SKIP 这台机器上没有可用的 JDK（${JAVA_HOME}），跑不了这条。"
  exit 77
fi
echo "   java: ${RUN_JAVA}"

echo ""
echo "== 1. 构建 fat jar =="
( cd "$ROOT" && $MVN -q -f "$ROOT/pom.xml" -DskipTests package ) > "$WORK/build.log" 2>&1
check "构建 fat jar" "0" "$?"
[ -f "$JAR" ] && ok "fat jar 在位" || { bad "fat jar 不在: $JAR"; exit 1; }

echo ""
echo "== 2. 造样本（txt 与 docx，敏感值放在正文里）=="
PHONE="13800138000"
printf '联系人张三，手机 %s。\n' "$PHONE" > "$WORK/样本.txt"
"$PY" "$ROOT/tools/gen_ooxml_sample.py" --out "$WORK/ooxml" > "$WORK/gen.log" 2>&1
check "生成 OOXML 样本" "0" "$?"
[ -f "$WORK/ooxml/case.docx" ] && ok "docx 样本在位" || { bad "docx 样本没生成"; cat "$WORK/gen.log"; exit 1; }

# 口令文件（口令从文件取，不经过命令行 —— 命令行会进 shell 历史与 ps 输出）
printf '我的口令\n' > "$WORK/pw.txt"
printf '别的口令\n' > "$WORK/wrong-pw.txt"

echo ""
echo "== 3. 给了口令：映射件是封装格式，且文件里没有原值 =="
"$RUN_JAVA" -jar "$JAR" redact "$WORK/样本.txt" --map-pass-file "$WORK/pw.txt" \
  > "$WORK/redact-sealed.out" 2>&1
check "redact（给了口令）退出码" "0" "$?"
MAP="$WORK/样本.txt.redacted.map.json"
[ -f "$MAP" ] && ok "映射件产出" || { bad "映射件没产出"; cat "$WORK/redact-sealed.out"; exit 1; }

# 【关键判据】独立标尺：结构对不对 + 原值在不在文件里。
"$PY" "$ROOT/tools/verify_sealed_map.py" "$MAP" --forbid "$PHONE" > "$WORK/verify.out" 2>&1
check "独立标尺判定（结构 + 原值不在文件里）" "0" "$?"
sed 's/^/    | /' "$WORK/verify.out"
# 标尺自己也要能咬住：造一份「假加密」——把整张映射表 base64 一遍塞进 ciphertext，
# 也就是最省事的那种「加密」（其实就是换了个编码）。标尺必须报红，否则它恒真。
cp "$MAP" "$WORK/标尺自检.json"
"$PY" - "$WORK/标尺自检.json" "$PHONE" <<'PYEOF'
import base64, json, sys
path, value = sys.argv[1], sys.argv[2]
doc = json.load(open(path, encoding="utf-8"))
fake = json.dumps({"mapping": {"<手机号1>": value}}, ensure_ascii=False).encode("utf-8")
doc["ciphertext"] = base64.b64encode(fake).decode("ascii")
json.dump(doc, open(path, "w", encoding="utf-8"), ensure_ascii=False)
PYEOF
"$PY" "$ROOT/tools/verify_sealed_map.py" "$WORK/标尺自检.json" --forbid "$PHONE" > "$WORK/verify-self.out" 2>&1
check "标尺自证：把原值塞回去，标尺必须报红" "1" "$?"

echo ""
echo "== 4. 同一个口令：还原逐字一致 =="
"$RUN_JAVA" -jar "$JAR" restore "$WORK/样本.redacted.txt" --map "$MAP" \
  --map-pass-file "$WORK/pw.txt" > "$WORK/restore.out" 2>&1
check "restore（同一个口令）退出码" "0" "$?"
if diff -q "$WORK/样本.txt" "$WORK/样本.redacted.restored.txt" >/dev/null 2>&1; then
  ok "还原后与原件逐字一致"
else
  bad "还原后与原件不一致"
  diff "$WORK/样本.txt" "$WORK/样本.redacted.restored.txt" 2>&1 | sed 's/^/    | /'
fi
notcontains "脱敏件里没有原值" "$WORK/样本.redacted.txt" "$PHONE"

echo ""
echo "== 5. 错口令：必须失败 =="
"$RUN_JAVA" -jar "$JAR" restore "$WORK/样本.redacted.txt" --map "$MAP" \
  --map-pass-file "$WORK/wrong-pw.txt" > "$WORK/restore-wrong.out" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then ok "错口令时退出码非 0（实际 ${rc}）"; else bad "错口令竟然成功了"; fi
contains "错口令时明确说口令不对" "$WORK/restore-wrong.out" "口令不对"
notcontains "错口令的输出里不出现原值" "$WORK/restore-wrong.out" "$PHONE"

echo ""
echo "== 6. 密文被改一个字节：必须失败（认证标签真的在校验）=="
cp "$MAP" "$WORK/篡改.json"
"$PY" - "$WORK/篡改.json" <<'PYEOF'
import base64, json, sys
path = sys.argv[1]
doc = json.load(open(path, encoding="utf-8"))
raw = bytearray(base64.b64decode(doc["ciphertext"]))
raw[0] ^= 0xFF
doc["ciphertext"] = base64.b64encode(bytes(raw)).decode("ascii")
json.dump(doc, open(path, "w", encoding="utf-8"), ensure_ascii=False)
PYEOF
"$RUN_JAVA" -jar "$JAR" restore "$WORK/样本.redacted.txt" --map "$WORK/篡改.json" \
  --map-pass-file "$WORK/pw.txt" > "$WORK/restore-tampered.out" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then ok "密文被改时退出码非 0（实际 ${rc}）"; else bad "密文被改竟然还原成功了"; fi
contains "密文被改时明确报错" "$WORK/restore-tampered.out" "口令不对"

echo ""
echo "== 7. 不给口令去还原封装件：必须明确失败，不许「静默什么都不替换」==="
"$RUN_JAVA" -jar "$JAR" restore "$WORK/样本.redacted.txt" --map "$MAP" > "$WORK/restore-nopw.out" 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then ok "不给口令时退出码非 0（实际 ${rc}）"; else bad "不给口令竟然成功了"; fi
contains "不给口令时明确说需要口令" "$WORK/restore-nopw.out" "需要口令"

echo ""
echo "== 8. 不给口令：明文映射件照旧能还原（向后兼容没被破坏）=="
"$RUN_JAVA" -jar "$JAR" redact "$WORK/样本.txt" > "$WORK/redact-plain.out" 2>&1
check "redact（不给口令）退出码" "0" "$?"
PLAINMAP="$WORK/样本.txt.redacted.map.json"
contains "明文映射件里仍然有原值（向后兼容）" "$PLAINMAP" "$PHONE"
"$RUN_JAVA" -jar "$JAR" restore "$WORK/样本.redacted.txt" --map "$PLAINMAP" \
  > "$WORK/restore-plain.out" 2>&1
check "restore（明文映射件）退出码" "0" "$?"
if diff -q "$WORK/样本.txt" "$WORK/样本.redacted.restored.txt" >/dev/null 2>&1; then
  ok "明文映射件还原后与原件逐字一致"
else
  bad "明文映射件还原后与原件不一致"
fi

echo ""
echo "== 9. 映射件权限：只给属主读 =="
if stat -f %A "$PLAINMAP" >/dev/null 2>&1; then
  perm=$(stat -f %Lp "$PLAINMAP")
  check "明文映射件权限" "600" "$perm"
  perm2=$(stat -f %Lp "$MAP")
  check "封装映射件权限" "600" "$perm2"
else
  echo "   本机 stat 不支持 %Lp，跳过权限检查"
fi

echo ""
echo "== 10. docx 也一样（映射件里没有原值，且能还原）=="
"$RUN_JAVA" -jar "$JAR" redact "$WORK/ooxml/case.docx" --map-pass-file "$WORK/pw.txt" \
  > "$WORK/redact-docx.out" 2>&1
check "redact docx（给了口令）退出码" "0" "$?"
DOCXMAP="$WORK/ooxml/case.docx.redacted.map.json"
[ -f "$DOCXMAP" ] && ok "docx 映射件产出" || { bad "docx 映射件没产出"; cat "$WORK/redact-docx.out"; exit 1; }
IDV="11010519491231002X"
"$PY" "$ROOT/tools/verify_sealed_map.py" "$DOCXMAP" --forbid "$IDV" --forbid "$PHONE" \
  > "$WORK/verify-docx.out" 2>&1
check "docx 映射件：独立标尺判定（结构 + 原值不在文件里）" "0" "$?"
sed 's/^/    | /' "$WORK/verify-docx.out"
"$RUN_JAVA" -jar "$JAR" restore "$WORK/ooxml/case.redacted.docx" --map "$DOCXMAP" \
  --map-pass-file "$WORK/pw.txt" > "$WORK/restore-docx.out" 2>&1
check "restore docx（同一个口令）退出码" "0" "$?"
if [ -f "$WORK/ooxml/case.redacted.restored.docx" ]; then
  ok "docx 还原件产出"
else
  bad "docx 还原件没产出"
fi

echo ""
echo "=== e2e-sealed-map: pass=$pass fail=$fail ==="
[ "$fail" -eq 0 ] || exit 1
exit 0
