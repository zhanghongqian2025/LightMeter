#!/usr/bin/env bash
# 把 NER 模型目录打成「模型包.zip」+ 同名 .sha256 旁挂文件。
#
# 【为什么要有这个脚本】
#   方案 B 把模型包从核心包里拆了出去（核心包 ≤90MiB 才是硬指标）。
#   拆出去的代价是：用户手上**没有**模型包是常态，而没有模型包时
#   中文人名与机构名**不会**被脱敏——那不是「少一个功能」，
#   是一份没脱干净的文档。所以模型包必须有一条不靠手工复制目录的安装路径。
#
# 【为什么要确定性】
#   模型包要独立分发。分发出去的东西，用户得能核对「我拿到的这份，
#   和官方公布的摘要一致吗」。zip 默认会写 mtime、条目顺序看目录遍历顺序、
#   权限位随 umask 变 —— 同一个模型目录在两台机器上打出两个摘要，
#   「核对摘要」就无从谈起。所以时间戳/顺序/权限位/压缩级别全都钉死
#   （细节见 tools/package_model.py 的文档串）。
#
# 【两道闸门，分工不重叠】
#   闸门一（本脚本的 Python 侧）：清单 ↔ 磁盘文件一致（文件在不在、摘要对不对）。
#   闸门二（Java 侧 `model verify`）：模型**真的能加载**（自包含、模型与词表配套）。
#   两边各管一半，谁也不重复谁。闸门二缺 jar 时**显式报「跳过」**，
#   不静默当通过 —— 一个从不报警的闸门比没有闸门更糟。
#
# 用法:
#   ./tools/package-model.sh                       # 打 ~/.litemask/models/ner
#   ./tools/package-model.sh --src <模型目录> --out-dir <输出目录>
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# JDK/Maven 的位置**不再写死**：旧机器上的路径早已搬走，写死会让脚本在第一步就失败。
. "$ROOT/tools/toolchain.sh"
export PATH="$JAVA_HOME/bin:$PATH"
PY="${PY:-python3}"

SRC="${LITEMASK_MODEL_SRC:-$HOME/.litemask/models/ner}"
OUT_DIR="${LITEMASK_MODEL_OUT:-$ROOT/dist}"

while [ $# -gt 0 ]; do
  case "$1" in
    --src) SRC="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

echo "== 1/3 校验清单并打确定性 zip =="
"$PY" "$ROOT/tools/package_model.py" pack --src "$SRC" --out-dir "$OUT_DIR" || exit 1

MODEL_ID="$("$PY" -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['id'])" "$SRC/manifest.json")"
ZIP="$OUT_DIR/$MODEL_ID.zip"
SIDECAR="$OUT_DIR/$MODEL_ID.zip.sha256"

echo ""
echo "== 2/3 核对旁挂摘要（拿发布出去的数字自己对一遍）=="
"$PY" "$ROOT/tools/package_model.py" check --sidecar "$SIDECAR" || exit 1

echo ""
echo "== 3/3 Java 侧闸门：模型必须真的能加载 =="
JAR="$ROOT/target/desens-core.jar"
if [ -f "$JAR" ]; then
  if "$JAVA_HOME/bin/java" -jar "$JAR" model verify "$SRC"; then
    echo "PASS 模型包能加载"
  else
    echo "FAIL 模型包加载不过——不该把它发出去" >&2
    exit 1
  fi
else
  # 缺 jar 时不能当成通过。这不是「没事」，是「这一项没验」。
  echo "SKIP 没找到 ${JAR}，跳过「模型能否加载」这一闸门"
  echo "     （先跑 mvn package 再跑一次本脚本，才能拿到这一项的结论）"
fi

echo ""
echo "产物: $ZIP"
echo "旁挂: $SIDECAR"
