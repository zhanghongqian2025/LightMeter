#!/usr/bin/env bash
# 命令行帮助：端到端。
#
# 【这一块为什么单独立一个 e2e】
#   块 37 开工前的实测：真二进制 `--help` 的用法块里**完全没有**
#   `autostart` 整块、`model download`、`model trust`（add/list/remove），
#   `--max-upload-bytes` / `--allow-unsigned` / `--sig-url` / `--task-timeout`
#   四个开关各出现 0 次 —— 而 README 都在教、实现里也都存在。
#   → 用户照 README 敲，help 里查不到；照 help 敲，又少一半能力。
#
# 【这个 e2e 测的是「help 说的话，CLI 真的认」】
#   守卫（work/check-cli-help-readme.py）做的是**文本层**的比对：
#   README 与 help 互为覆盖、子命令声明被顶层 help 覆盖。
#   那一层**证明不了** help 里写的命令真能跑 —— 文本可以对，行为可以对不上。
#   所以这里补一层**行为探针**：
#     对 help 用法块里的**每一条**命令形式，真的把 CLI 调起来，
#     要求它**认下这条命令**（不报「未知子命令 / 未知动作」）。
#
# 【探针怎么做到没有副作用】
#   用一个**不存在的选项** `--zzz-not-a-real-option` 追加在命令后面：
#     · 命令本身不认识 → CLI 会打「未知子命令: …」→ 判红；
#     · 命令认识、只是不认这个选项 → 报的是别的错（未知选项 / 参数不对 /
#       文件不存在 / 装不上），**不会**打「未知子命令」。
#   于是「认不认识这条命令」和「这个选项对不对」两件事被分开了。
#   所有调用都走 e2e-common.sh 的 prod（user.home 隔离到空目录），
#   真的动文件也不会碰到开发机上的 ~/.litemask。
#
# 【判据独立于被测实现】
#   help 文本、README、子命令用法都是**跑出来的**；
#   解析用 awk 另写一份（守卫那份是 python），不复用被测的任何 Java 代码。
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
README="$ROOT/README.md"
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
if [ ! -f "$README" ]; then
  echo "FAIL 找不到 README（$README）" >&2
  exit 1
fi

# 程序名取自**跨端唯一真源**，不在这里写死一个字面量。
APP_NAME="$(sed -n 's/^app.name=//p' "$IDENTITY" | head -1)"
if [ -z "$APP_NAME" ]; then
  echo "FAIL $IDENTITY 里读不到 app.name" >&2
  exit 1
fi

echo "== 1. 跑真二进制的 --help =="
prod -jar "$JAR" --help > "$WORK/help.txt" 2> "$WORK/help.err"
rc=$?
check "--help 退出码" "0" "$rc"
if [ ! -s "$WORK/help.txt" ]; then
  echo "FAIL --help 没有输出（stderr：$(head -3 "$WORK/help.err")）" >&2
  exit 1
fi
echo "   help 共 $(wc -l < "$WORK/help.txt" | tr -d ' ') 行"

# ---- awk 解析（与守卫那份 python 独立）----
# 用法块 = 从「用法:」那一行之后、到第一个空行为止。
extract_forms() { # $1=文件
  awk '
    /用法:/ { inblock=1; next }
    inblock && /^[[:space:]]*$/ { inblock=0; next }
    inblock {
      line=$0
      sub(/^[[:space:]]+/, "", line)
      if (match(line, /^[A-Za-z][A-Za-z0-9_-]*([ \t]+[A-Za-z][A-Za-z0-9_-]*)*/)) {
        s=substr(line, RSTART, RLENGTH)
        gsub(/[ \t]+/, " ", s)
        print s
      }
    }
  ' "$1"
}
# 全文里的长开关（--max-upload-bytes）
extract_long_flags() { # $1=文件
  awk '{
    rest=$0
    while (match(rest, /--[a-z][a-z0-9-]*/)) {
      print substr(rest, RSTART, RLENGTH)
      rest=substr(rest, RSTART+RLENGTH)
    }
  }' "$1"
}
# README 里「属于这个 CLI」的开关：代码块内只认以程序名开头的行，代码块外全算
extract_readme_cli_flags() {
  awk -v app="$APP_NAME" '
    /^[[:space:]]*(>[[:space:]]*)*```/ { infence=!infence; next }
    {
      line=$0
      if (infence && index(line, app " ") != 1) next
      rest=line
      while (match(rest, /--[a-z][a-z0-9-]*/)) {
        print substr(rest, RSTART, RLENGTH)
        rest=substr(rest, RSTART+RLENGTH)
      }
    }
  ' "$README"
}
# README 代码块里的命令行 → 命令形式
extract_readme_forms() {
  awk -v app="$APP_NAME" '
    /^[[:space:]]*(>[[:space:]]*)*```/ { infence=!infence; next }
    infence {
      line=$0
      sub(/^[[:space:]]+/, "", line)
      if (index(line, app " ") == 1) {
        sub("^" app " ", "", line)
        sub(/[ \t]+#.*$/, "", line)
        if (match(line, /^[A-Za-z][A-Za-z0-9_-]*([ \t]+[A-Za-z][A-Za-z0-9_-]*)*/)) {
          s=substr(line, RSTART, RLENGTH)
          gsub(/[ \t]+/, " ", s)
          print s
        }
      }
    }
  ' "$README"
}

extract_forms "$WORK/help.txt" > "$WORK/help.forms"
extract_long_flags "$WORK/help.txt" | sort -u > "$WORK/help.flags"
extract_readme_forms "$README" | sort -u > "$WORK/readme.forms"
extract_readme_cli_flags "$README" | sort -u > "$WORK/readme.flags"

echo "   help 命令形式 $(wc -l < "$WORK/help.forms" | tr -d ' ') 条，README 命令形式 $(wc -l < "$WORK/readme.forms" | tr -d ' ') 条"

echo ""
echo "== 2. 行为探针：help 里的每条命令，CLI 真的认 =="
probe_out="$WORK/probe.out"
while IFS= read -r form; do
  [ -z "$form" ] && continue
  # shellcheck disable=SC2086
  prod -jar "$JAR" $form --zzz-not-a-real-option > "$probe_out" 2>&1
  if grep -q "未知子命令" "$probe_out" || grep -q "未知动作" "$probe_out"; then
    bad "CLI 不认 help 里写的命令：${form}（报：$(grep -m1 -e '未知子命令' -e '未知动作' "$probe_out")）"
  else
    ok "CLI 认得 help 里写的命令：${form}"
  fi
done < "$WORK/help.forms"

echo ""
echo "== 3. README ⊆ help（README 教的每条命令都在 help 里）=="
# 【为什么是「前缀覆盖」而不是完全相等】
#   README 的一行里，命令后面可能还跟着参数，而参数也可能像英文词
#   （`model download https://…` 的标识符串是 "model download https"）。
#   help 里的命令形式是 "model download" —— 它是那一行的**前缀**，就是覆盖。
#   完全相等会把这种正常情况判成红。
#
# 【块 38 补的一个方向：组形式】
#   README 教 `LightMeter model --help` 时，它的标识符串是 "model" ——
#   而 help 的用法块里列的是 "model install" / "model download" / … 这些**动作**，
#   并没有一条叫 "model"。→ 单向前缀覆盖会把它判红。
#   → 补上另一个方向：**README 的形式是 help 某条形式的前缀**时也算覆盖
#     （组名被 help 摊开过 = help 教了它）。
#   → 这个方向只作用于「README ⊆ help」这一段。
#     判据5（help ⊆ README）不认它：help 里那十条 model 动作仍然要 README 自己逐条教。
awk '
  NR==FNR { help[$0]=1; next }
  {
    covered=0
    for (h in help) {
      if ($0 == h || index($0, h " ") == 1 || index(h, $0 " ") == 1) { covered=1; break }
    }
    if (!covered) print $0
  }
' "$WORK/help.forms" "$WORK/readme.forms" > "$WORK/readme.uncovered"
covered_n=$(grep -c . "$WORK/readme.uncovered" || true)
total_n=$(grep -c . "$WORK/readme.forms" || true)
if [ "$covered_n" -eq 0 ]; then
  ok "README 教的 ${total_n} 条命令 help 全都有"
else
  while IFS= read -r form; do
    [ -z "$form" ] && continue
    bad "README 教的命令 help 里没有：${form}"
  done < "$WORK/readme.uncovered"
fi

echo ""
echo "== 4. README ⊆ help（README 教的每个开关都在 help 里）=="
while IFS= read -r flag; do
  [ -z "$flag" ] && continue
  if grep -Fxq -- "$flag" "$WORK/help.flags"; then
    ok "README 教的开关 help 里有：${flag}"
  else
    bad "README 教的开关 help 里没有：${flag}"
  fi
done < "$WORK/readme.flags"

echo ""
echo "== 5. help ⊆ README（help 里的每个开关 README 都写了）=="
while IFS= read -r flag; do
  [ -z "$flag" ] && continue
  if grep -qF -- "$flag" "$README"; then
    ok "help 里的开关 README 写了：${flag}"
  else
    bad "help 里的开关 README 没写：${flag}"
  fi
done < "$WORK/help.flags"

echo ""
echo "== 6. 子命令自己声明的，顶层 help 必须全覆盖 =="
for sub in model autostart; do
  prod -jar "$JAR" "$sub" > "$WORK/$sub.txt" 2>&1
  extract_forms "$WORK/$sub.txt" > "$WORK/$sub.forms"
  extract_long_flags "$WORK/$sub.txt" | sort -u > "$WORK/$sub.flags"
  while IFS= read -r form; do
    [ -z "$form" ] && continue
    if grep -Fxq -- "$form" "$WORK/help.forms"; then
      ok "$sub 声明的命令顶层 help 里有：${form}"
    else
      bad "$sub 声明的命令顶层 help 里没有：${form}"
    fi
  done < "$WORK/$sub.forms"
  while IFS= read -r flag; do
    [ -z "$flag" ] && continue
    if grep -Fxq -- "$flag" "$WORK/help.flags"; then
      ok "$sub 声明的开关顶层 help 里有：${flag}"
    else
      bad "$sub 声明的开关顶层 help 里没有：${flag}"
    fi
  done < "$WORK/$sub.flags"
done

echo ""
echo "== 7. 前提自证 =="
# help 的用法块不能是空的：解析器坏掉时上面那些断言会**全部空转**
if [ ! -s "$WORK/help.forms" ]; then
  bad "前提自证：help 的用法块解析出 0 条命令 —— 上面那些断言等于没跑"
else
  echo "OK 前提自证：help 的用法块解析出 $(wc -l < "$WORK/help.forms" | tr -d ' ') 条命令形式"
fi
if [ ! -s "$WORK/readme.forms" ]; then
  bad "前提自证：README 的命令行解析出 0 条命令 —— 上面那些断言等于没跑"
else
  echo "OK 前提自证：README 的命令行解析出 $(wc -l < "$WORK/readme.forms" | tr -d ' ') 条命令形式"
fi

echo ""
echo "=== e2e-cli-help: pass=${pass} fail=${fail} ==="
rm_rf "$WORK"
[ "$fail" -eq 0 ] || exit 1
exit 0
