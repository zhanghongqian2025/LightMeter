#!/usr/bin/env bash
# M1-f 端到端：不信任文档。
#
# 这一步刻意不复用单测里的任何代码，也不复用被测的限额逻辑——
# 炸弹是 python 用 zipfile 现造的，判定只看**命令行退出码、stderr 文本、
# 以及输出文件到底存不存在**。这三个信号都来自 jar 的外部行为。
#
# 单测能证明"限额逻辑在 TINY 上会触发"，证明不了"默认上限下面对一个
# 597KB 的真实炸弹，CLI 会干净地退出而不是把机器拖垮"。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 打包用完整 JDK，跑产品用可替换的 java——理由见 tools/e2e-serve.sh 头部注释。
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
RUN_JAVA="${LITEMASK_RUN_JAVA:-$JAVA_HOME/bin/java}"
if [ "${LITEMASK_RUN_JAVA:-}" != "" ]; then
  echo "运行产品用的 java：${RUN_JAVA}（打包仍用 ${JAVA_HOME}）"
fi
MVN="$MVN_BIN"

WORK="$(mktemp -d)"
JAR="$ROOT/target/desens-core.jar"
pass=0; fail=0

say() { printf '%s\n' "$*"; }
ok()  { say "PASS $1"; pass=$((pass + 1)); }
no()  { say "FAIL $1${2:-}"; fail=$((fail + 1)); }

# 运行形态由 e2e-common 决定（默认无模型 = pkg 交付形态；LITEMASK_E2E_NER=<目录> 则带模型）。
# 这一支以前会随开发机装没装 NER 模型而变：无模型全绿、装了 v9 就红
# （VIN 被 NER 抢先认成人名/机构名，映射里自然没有原值）。测试必须自己决定形态。
. "$ROOT/tools/e2e-common.sh"
e2e_form_banner
# 清理的 trap 注册在 e2e-common 之后：trap 里的 rm_rf 由那里 source 的
# shell-lib.sh 提供。注册在前面的话，脚本若在中间提前退出，
# EXIT trap 会报「rm_rf: command not found」——清理没做，还多一条误导性报错。
trap 'rm_rf "$WORK"' EXIT

if [ ! -f "$JAR" ]; then
  say "先打包 fat jar ..."
  $MVN -B -o -q -Dstyle.color=never -f "$ROOT/pom.xml" package >/dev/null 2>&1 || {
    say "打包失败，端到端无法进行"; exit 1; }
fi
say "jar = $JAR ($(wc -c < "$JAR" | tr -d ' ') 字节)"

# ---------------------------------------------------------------- 造样本
python3 - "$WORK" <<'MAKESAMPLES'
import os, sys, zipfile
work = sys.argv[1]
jo = lambda n: os.path.join(work, n)

CT = '<?xml version="1.0"?><Types><Default Extension="xml" ContentType="x"/></Types>'

# 1) zip 炸弹：约 597KB 的包，解压后 600MB
with zipfile.ZipFile(jo("bomb.docx"), "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", CT)
    z.writestr("word/document.xml",
               '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r>'
               '<w:t>13800138000</w:t></w:r></w:p></w:body></w:document>')
    with z.open("word/media/huge.bin", "w") as f:
        chunk = b"A" * (1 << 20)
        for _ in range(600):
            f.write(chunk)

# 2) 路径穿越条目名
with zipfile.ZipFile(jo("slip.docx"), "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("../escape.xml", "<a>13800138000</a>")

# 3) XXE：外部实体指向本地"机密"文件
secret = jo("secret.txt")
open(secret, "w").write("TOP-SECRET-E2E-MARKER")
with zipfile.ZipFile(jo("xxe.docx"), "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("word/document.xml",
               '<?xml version="1.0"?>'
               '<!DOCTYPE root [<!ENTITY xxe SYSTEM "file://%s">]>'
               '<root>&xxe;</root>' % secret)

# 4) 超深嵌套
with zipfile.ZipFile(jo("deep.docx"), "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("word/document.xml", "<a>" * 20000 + "13800138000" + "</a>" * 20000)

# 5) 正常文档：四个标识符，用来证明加固没有过头
with zipfile.ZipFile(jo("normal.docx"), "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", CT)
    z.writestr("word/document.xml",
               '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r>'
               '<w:t>联系人 13800138000，证件 11010519491231002X，'
               '发票号码 24112000000048542163，车架号 1HGCM82633A004352。'
               '</w:t></w:r></w:p></w:body></w:document>')

print("BOMB_PACKED=%d 字节" % os.path.getsize(jo("bomb.docx")))
MAKESAMPLES

# ---------------------------------------------------------------- 恶意输入必须被拒

expect_reject() { # $1=名称 $2=文件 $3=错误关键词
  local name="$1" file="$2" want="$3"
  local out="${file%.docx}.out.docx"
  rm -f "$out"
  local err code
  err=$(prod -Xmx256m -jar "$JAR" redact "$file" -o "$out" 2>&1)
  code=$?

  if [ "$code" -eq 0 ]; then
    no "$name" "退出码为 0，恶意文件被当正常文件接受了"; return
  fi
  if [ "$code" -eq 134 ] || [ "$code" -eq 137 ] || [ "$code" -eq 139 ]; then
    no "$name" "进程被信号打死（code=$code{code}，说明限额没兜住"; say "  $err"; return
  fi
  if ! printf '%s' "$err" | grep -qF "$want"; then
    no "$name" "报错了但原因不对，期望包含「$want{want}，实际输出："; say "  $err"; return
  fi
  if [ -e "$out" ] && [ -s "$out" ]; then
    no "$name" "留下了非空输出文件 $out"; return
  fi
  ok "$name" "退出码 $code{code}已拒绝：$(printf '%s' "$err" | tail -1 | cut -c1-80)"
}

expect_reject "zip 炸弹（597KB → 600MB）" "$WORK/bomb.docx" "单条目上限"
expect_reject "路径穿越条目名"           "$WORK/slip.docx" "穿越"
expect_reject "XXE 外部实体"             "$WORK/xxe.docx"  "无法解析"
expect_reject "20000 层深嵌套"           "$WORK/deep.docx" "嵌套深度"

# ---------------------------------------------------------------- 对照：正常文档必须照常工作

say ""
OUT="$WORK/normal.out.docx"
MSG=$(prod -jar "$JAR" redact "$WORK/normal.docx" -o "$OUT" 2>&1)
code=$?
echo "$MSG" > "$WORK/normal.out.txt"
e2e_form_check "$WORK/normal.out.txt"

if [ "$code" -ne 0 ]; then
  no "正常文档被误拒（加固过头了）"; say "  ${MSG}"
else
  # 独立读取器：只用 python 的 zipfile 打开产物，不碰任何被测代码
  if python3 - "$OUT" <<'SCANS'
import sys, zipfile
need = ["13800138000", "11010519491231002X",
        "24112000000048542163", "1HGCM82633A004352"]
blob = b""
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in z.namelist():
        blob += z.read(n)
sys.exit(1 if any(x.encode() in blob for x in need) else 0)
SCANS
  then
    ok "正常文档：手机/身份证/发票号码/车架号 四个值全包扫描 0 残留"
  else
    no "正常文档脱敏后仍有原文残留"
  fi

  # 映射文件才是"引擎认出了哪些"的证据。产物无残留只证明文件被改过，
  # 证明不了四个值都分别被识别——也可能只命中了其中两个。
  MAPF="$WORK/normal.docx.redacted.map.json"
  if python3 - "$MAPF" <<'MAPCHK'
import json, sys
text = open(sys.argv[1], encoding="utf-8").read()
need = {"13800138000": "手机号", "11010519491231002X": "身份证",
        "24112000000048542163": "发票号码", "1HGCM82633A004352": "车架号"}
missing = [f"{k}({v})" for k, v in need.items() if k not in text]
if missing:
    print("  映射文件里找不到: " + ", ".join(missing))
    sys.exit(1)
sys.exit(0)
MAPCHK
  then
    ok "映射文件四个原值齐全（发票号码与车架号这两条 M1-f 新规则确实接进了 CLI 链路）"
  else
    no "映射文件里缺原值：引擎没认出全部四个标识符"
  fi
fi

say ""
say "=== e2e-untrusted: pass=$pass fail=$fail ==="
[ "$fail" -eq 0 ]
