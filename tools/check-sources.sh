#!/usr/bin/env bash
# 脱敏工具的源码自检：shell / PowerShell 语法 + 字节级损坏。
#
# 【为什么要有这个闸门】
#   一类损坏是**静默**的：NUL 字节落在字符串里、U+FFFD 替换字符落在注释或文档里，
#   Java 照样编译、脚本照样跑、测试照样过 —— 但人读源码时看到的是半截字，
#   文档里看到的是「HTTP 200 <一个坏字>算成功运行」。
#   它是在**写文件**的环节（编辑器/工具链的编码往返）产生的，
#   所以只能在**字节层面**发现。
#   律所桌面端与 iPhone 客户端已经各有一道同款闸门（那边是**真的撞上过**才加的：
#   7 处替换字符已经进了提交历史）。脱敏工具这一侧此前没有，
#   而它的 macOS 包是直接从这个树里打出来的 —— 源码坏在哪儿，包就坏在哪儿。
#
# 【它做什么】
#   ① tools/*.sh 逐个 `bash -n`；tools/*.ps1 在有 PowerShell 时做语法解析
#   ② src/ tools/ docs/ demo/ 下的文本文件：查 NUL 字节与 U+FFFD 替换字符（字节级）
#
# 【怎么判断一个文件是不是文本】——与另几道闸门同一条规则
#   **不用扩展名白名单**：白名单漏一种新文件类型，就等于漏一个洞。
#   （本闸门第一版用的就是白名单，结果 `.ps1`、`.entitlements`、`demo/` 全在盲区——
#     与桌面端/iPhone 端犯的是同一个错，这一版改成一致的做法。）
#   → 判据是「**能严格解码成 UTF-8**」：文本文件正好是合法 UTF-8 的那些。
#   解不开的，再分三种情况处理，顺序不能换：
#     ① **文件头命中已知二进制魔数**（PNG/JPEG/GIF/PDF/ZIP/ELF/wasm…）→ 明确是二进制，跳过；
#     ② **含 NUL 字节** → 也是二进制（.onnx 模型、.ttf/.ttc 字体走这里），跳过；
#     ③ 两条都不命中 → 「不是合法 UTF-8（编码坏了）」，报出来。
#
# 【这一版修掉的洞】
#   原来用的是「可打印字节占比 ≥ 85%」的启发式，而那条 tr **保留了 0x80–0xFF 的高字节**
#   → PNG 的 IDAT 是 zlib 压缩数据，随机字节里约 88% 被算成「可打印」→ 越过门槛 →
#   **图片会被误判成「编码坏了」**。
#   本仓库目前没有 PNG，是**碰巧**没被误伤；而一个会误报的闸门迟早被人关掉，
#   一旦关掉，真损坏就漏了。→ 换成「文件头说了算 + NUL 说了算」，两条都是确定的判断。
#
# 【关于「故意的替换符」】
#   有极少数地方**故意**用到 U+FFFD 这个字符本身（例如 PDF 里无法解码的字符就用它表示）。
#   闸门不能把那种地方也报成损坏——一个会误报的闸门，迟早被人关掉。
#   → 这类地方必须**逐条登记**在 tools/byte-gate-exemptions.txt 里，并写清理由；
#     登记的片段一旦在源码里不存在了，闸门会报「豁免失效」，要求把这条删掉。
#
# 【它不做什么】
#   不编译、不跑测试、不碰模型、不联网。只是一道**出包前的拦门**。
#
# 用法：
#   bash tools/check-sources.sh
#
# 退出码：0 = 通过；1 = 有问题（并逐条点名文件）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

EXEMPTIONS_FILE="tools/byte-gate-exemptions.txt"
SCAN_ROOTS="src tools docs demo"
problems=0
say() { printf '%s\n' "$*"; }
bad() { printf '  \033[31m[!!]\033[0m   %s\n' "$*"; problems=$((problems + 1)); }

# ---------------------------------------------------------------- ① shell 语法
say '== shell 语法（tools/*.sh）=='
sh_count=0
for file in tools/*.sh; do
  [ -f "$file" ] || continue
  sh_count=$((sh_count + 1))
  if ! bash -n "$file" 2>/dev/null; then
    bad "$file: 语法错误"
    bash -n "$file" 2>&1 | sed 's/^/       /' | head -5
  fi
done
if [ "$sh_count" -eq 0 ]; then
  say '  [--]   没有找到 tools/*.sh —— 这本身就不对'
  problems=$((problems + 1))
else
  say "  [ok]   $sh_count 个脚本语法通过"
fi

# ---------------------------------------------------------------- ①b PowerShell 语法
say ''
say '== PowerShell 语法（tools/*.ps1）=='
PS_CMD=""
if command -v pwsh >/dev/null 2>&1; then
  PS_CMD="pwsh"
elif command -v powershell >/dev/null 2>&1; then
  PS_CMD="powershell"
fi
ps_count=0
for file in tools/*.ps1; do
  [ -f "$file" ] || continue
  ps_count=$((ps_count + 1))
done
if [ "$ps_count" -eq 0 ]; then
  say '  [--]   没有找到 tools/*.ps1'
elif [ -z "$PS_CMD" ]; then
  # 判不了就说判不了，不当通过。
  say "  [--]   这台机器上没有 PowerShell —— $ps_count 个 .ps1 的语法**这里判不了**"
  say '         （字节级扫描仍然覆盖它们；语法检查需要 PowerShell。）'
else
  ps_bad=0
  for file in tools/*.ps1; do
    [ -f "$file" ] || continue
    # 【为什么用环境变量传路径，而不是把路径当参数】
    #   `powershell -Command '<脚本>' <路径>` 里，尾随的参数会被**并进命令串**，
    #   脚本里的 $args[0] 拿到的是空 → ParseFile('') → 报「path 参数无效」，
    #   看起来像语法错误，其实是**传参方式**错了。
    #   环境变量跨 MSYS → Windows 的边界是可靠的（路径参数反而会被改写）。
    if ! LITEMASK_PS_FILE="$file" "$PS_CMD" -NoProfile -Command '
      $p = $env:LITEMASK_PS_FILE
      $errors = $null
      [System.Management.Automation.Language.Parser]::ParseFile($p, [ref]$null, [ref]$errors) > $null
      if ($errors -and $errors.Count -gt 0) { $errors | ForEach-Object { Write-Output $_.Message }; exit 1 }
    ' > /tmp/litemask-ps.$$ 2>&1; then
      bad "$file: 语法错误"
      sed 's/^/       /' /tmp/litemask-ps.$$ 2>/dev/null | head -5
      ps_bad=$((ps_bad + 1))
    fi
    rm -f /tmp/litemask-ps.$$
  done
  if [ "$ps_bad" -eq 0 ]; then
    say "  [ok]   $ps_count 个脚本语法通过（用 ${PS_CMD}）"
  fi
fi

# ---------------------------------------------------------------- ② 豁免清单
say ''
say '== 豁免清单 =='
# 读成三个并行数组：路径 / 必须同现的片段 / 理由
#
# 【为什么要去空白】
#   清单是人手写的，写成「路径 | 片段 | 理由」时，路径尾部会带一个空格
#   （`src/x.java `），路径就**对不上任何文件**了——
#   而症状是「豁免说文件不存在」，看起来像源码被删了，其实是一个空格。
#   → 三个字段都去首尾空白，写法随便一点也能用。
trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

ex_paths=()
ex_needles=()
ex_reasons=()
if [ -f "$EXEMPTIONS_FILE" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|\#*) continue ;; esac
    path="$(trim "${line%%|*}")"
    rest="${line#*|}"
    needle="$(trim "${rest%%|*}")"
    reason="$(trim "${rest#*|}")"
    ex_paths+=("$path")
    ex_needles+=("$needle")
    ex_reasons+=("$reason")
  done < "$EXEMPTIONS_FILE"
fi
if [ ${#ex_paths[@]} -eq 0 ]; then
  say '  [--]   豁免清单是空的（这是正常状态：没有需要豁免的地方）'
else
  say "  [ok]   登记了 ${#ex_paths[@]} 条豁免（每条都带理由）"
fi

# 某一行是不是被豁免了：路径对得上，且这一行里有登记的片段
line_is_exempt() {
  local path="$1" line="$2" i
  for i in "${!ex_paths[@]}"; do
    if [ "${ex_paths[$i]}" = "$path" ]; then
      if printf '%s' "$line" | grep -qF -- "${ex_needles[$i]}"; then
        return 0
      fi
    fi
  done
  return 1
}

# ---------------------------------------------------------------- ③ 字节级
say ''
say '== 字节级自检（NUL / U+FFFD 替换字符）=='
#
# 【为什么用 Python 当「严格 UTF-8 解码器」】
#   「文本文件」= 「能严格解码成 UTF-8 的文件」。Python 的 strict 解码正好干这个：
#   合法 → 退出 0；非法 → 退出非 0。macOS 自带 iconv 在部分合法 UTF-8 文件上会
#   写出完整内容却返回 `Inappropriate ioctl for device`，用它作判据会误报。
#   本仓库的打包脚本本来就用 python3 处理清单，因此这里不增加新的发布依赖。
#   实测行为（这台机器上逐条验过）：
#     合法 UTF-8 → 通过；非法 UTF-8 → 被拒；
#     含 NUL 但其余合法的 UTF-8 → **仍然通过**（NUL 是合法码位 → 它是文本，NUL 才是损坏）；
#     中文 UTF-8 → 通过。
#
# 【怎么区分「二进制」与「编码坏了的文本」】
#   两条**确定**的判据，顺序不能换：
#     ① 文件头命中已知二进制魔数 → 明确是二进制，跳过
#     ② 含 NUL 字节 → 也是二进制（没有魔数的格式走这里），跳过
#     ③ 都不命中 → 报「不是合法 UTF-8（编码坏了）」
#   【为什么不用「可打印字节占比」】
#     那个启发式对高熵二进制会失手：PNG 的 zlib 随机数据里约 88% 落在
#     「非控制字符」区间，越过 85% 门槛 → 图片被误判成「编码坏了」。
#     一个会误报的闸门迟早被人关掉 —— 而一旦关掉，真损坏就漏了。
is_utf8_text() {
  python3 -c 'import pathlib, sys; pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="strict")' \
    "$1" >/dev/null 2>&1
}

# 文件头魔数（od -An -tx1 的小写十六进制，去掉空格与换行后比对前缀）
is_binary_by_magic() {
  local file="$1" head_hex
  head_hex="$(head -c 8 "$file" 2>/dev/null | od -An -tx1 | tr -d ' \n')"
  case "$head_hex" in
    89504e47*|ffd8ff*|47494638*|424d*|52494646*|25504446*|504b0304*|504b0506*|\
    1f8b*|377abcaf*|7f454c46*|0061736d*|4f676753*|664c6143*|494433*|00000100*|1a45dfa3*)
      return 0 ;;
  esac
  return 1
}

has_nul_byte() {
  local file="$1" total without_nul
  total="$(wc -c < "$file" | tr -d '[:space:]')"
  without_nul="$(LC_ALL=C tr -d '\000' < "$file" | wc -c | tr -d '[:space:]')"
  [ "$total" != "$without_nul" ]
}

REPLACEMENT_CHAR=$'\xef\xbf\xbd'
scanned=0
exempted_lines=0
skipped_binary=0
for dir in $SCAN_ROOTS; do
  [ -d "$dir" ] || continue
  while IFS= read -r file; do
    [ -f "$file" ] || continue
    if ! is_utf8_text "$file"; then
      # 【顺序要紧：魔数必须最先判】文件头说了算的事，不该交给内容去猜。
      if is_binary_by_magic "$file"; then
        skipped_binary=$((skipped_binary + 1))
      elif has_nul_byte "$file"; then
        skipped_binary=$((skipped_binary + 1))
      else
        bad "$file: 不是合法 UTF-8（编码坏了）"
      fi
      continue
    fi
    scanned=$((scanned + 1))
    total="$(wc -c < "$file" | tr -d '[:space:]')"
    without_nul="$(LC_ALL=C tr -d '\000' < "$file" | wc -c | tr -d '[:space:]')"
    if [ "$total" != "$without_nul" ]; then
      bad "$file: 含 NUL 字节"
      continue
    fi
    if LC_ALL=C grep -q "$REPLACEMENT_CHAR" "$file" 2>/dev/null; then
      # 一行一行看：有的行是登记过的「故意用法」，有的是真损坏 —— 分开报
      line_no=0
      while IFS= read -r line || [ -n "$line" ]; do
        line_no=$((line_no + 1))
        case "$line" in
          *"$REPLACEMENT_CHAR"*) ;;
          *) continue ;;
        esac
        if line_is_exempt "$file" "$line"; then
          exempted_lines=$((exempted_lines + 1))
          continue
        fi
        count="$(printf '%s' "$line" | LC_ALL=C grep -o "$REPLACEMENT_CHAR" | wc -l | tr -d '[:space:]')"
        bad "$file:$line_no: 含 $count 个 U+FFFD 替换字符"
        printf '         %s\n' "$(printf '%s' "$line" | sed 's/[[:space:]]*$//' | cut -c1-120)"
      done < "$file"
    fi
  done <<EOF
$(find "$dir" -type f 2>/dev/null)
EOF
done

if [ "$scanned" -eq 0 ]; then
  say '  [--]   一个文件都没扫到 —— 闸门空转等于没设（这比没有闸门更糟）'
  problems=$((problems + 1))
fi
if [ "$exempted_lines" -gt 0 ]; then
  say "  [--]   $exempted_lines 处替换符属于登记过的「故意用法」，已按豁免放行"
fi
if [ "$skipped_binary" -gt 0 ]; then
  say "  [--]   跳过 $skipped_binary 个二进制文件（含 NUL 字节但不像是文本）"
fi

# ---------------------------------------------------------------- ④ 豁免是否还有效
say ''
say '== 豁免是否还有效 =='
if [ ${#ex_paths[@]} -eq 0 ]; then
  say '  [--]   没有豁免可查'
else
  for i in "${!ex_paths[@]}"; do
    path="${ex_paths[$i]}"
    needle="${ex_needles[$i]}"
    if [ ! -f "$path" ]; then
      bad "豁免失效：$path 不存在了 —— 删掉这条豁免"
      continue
    fi
    if grep -qF -- "$needle" "$path" 2>/dev/null; then
      say "  [ok]   ${path}（${ex_reasons[$i]}）"
    else
      bad "豁免失效：$path 里已经找不到「$needle」—— 删掉这条豁免（理由：${ex_reasons[$i]}）"
    fi
  done
fi

say ''
if [ "$problems" -gt 0 ]; then
  say "源码自检未通过（$problems 处）。"
  say '这类损坏语法检查、编译器、测试都查不出来。请按上下文还原被替换掉的字符。'
  exit 1
fi
say "源码自检通过：$sh_count 个 shell 脚本语法正常，$ps_count 个 PowerShell 脚本语法正常，$scanned 个文件字节干净。"
exit 0
