#!/usr/bin/env bash
# M3-4 体积闸门：三层实测 + 硬指标判定。
#
# 为什么要有这个脚本（而不是在文档里记一次数字）：
# 体积是**唯一一个「涨了不会有人发现」的东西**。功能坏了会红、测试会挂、
# 用户会报错；体积涨了 3MB 时一切都正常，只是文档不再说得对。
# 而本项目每加一个依赖（M3 加了 ONNX Runtime + Jackson + PDFBox）都会动它，
# 所以必须是一个每次都跑的检查，而不是文档里的一次性记录。
#
# 三层的口径（对应 docs/开发目标与路线图.md）：
#   L0  jlink 定制运行时（不含模块化 JDK 的多余部分）
#   L1  应用 + 核心依赖（fat jar：PDFBox / Jackson / OOXML / OFD / 前端资源）
#   L2  AI 包（NER 模型包）
#
# 硬指标：全量 L0+L1+L2 ≤ 90MB。超了 exit 1。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- 预算与三层口径：唯一真源是 tools/size-budget.properties ----
#
# 【为什么不再写死 90（2026-10-21 块 25）】
#   块 25 之前，这个 90 只写在这里；
#   而 Windows 侧的打包脚本（tools/package-windows.ps1）**根本没有闸门** ——
#   它会打印体积，然后照样打「PASS 打包并验证通过」。
#   → 也就是说：本项目**唯一的硬指标只在半个平台上生效**。
#   → 现在两端读**同一份**策略表：同一个事实只在一处定义。
#
# 【为什么用 sed 而不是 source 一个 .sh】
#   策略表是 java.util.Properties 格式（判定器在 Windows 上要用 JDK 读它，
#   那台机器不一定有 python）→ shell 侧用 sed 读同一份。
POLICY="${POLICY:-$ROOT/tools/size-budget.properties}"
if [ ! -f "$POLICY" ]; then
  echo "X 找不到体积闸门策略表：$POLICY"
  exit 1
fi
prop() { sed -n "s/^$1=//p" "$POLICY" | head -1; }
BUDGET_MIB="$(prop 'budget\.mib')"
if [ -z "$BUDGET_MIB" ]; then
  echo "X 策略表里没有 budget.mib：$POLICY"
  exit 1
fi
case "$BUDGET_MIB" in
  ''|*[!0-9]*) echo "X 策略表里的 budget.mib 不是整数：$BUDGET_MIB"; exit 1 ;;
esac
BUDGET_MB="$BUDGET_MIB"
BUDGET_BYTES=$((BUDGET_MIB * 1048576))
layer_name() { sed -n "s/^layer\.$1\.name=//p" "$POLICY" | head -1; }
for _lid in L0 L1 L2; do
  if [ -z "$(layer_name "$_lid")" ]; then
    echo "X 策略表里没有 layer.$_lid.name：$POLICY"
    exit 1
  fi
done

# 用 1024^2 还是 1000^2？两边都在用，所以必须**写死并说明**。
# 「MB」在 Windows 资源管理器上是 1000^2，在 macOS 的 ls -lh 与 jlink 里是 1024^2。
# 这里统一用 MiB（1024^2）比较保守（数字更小），而报给人看的两套都列出来，
# 免得「文档写 61MB、用户资源管理器显示 65MB」被当成造假。
# ---- 字节 → 两位小数：精确整数运算（不用 awk 的 %.2f）----
#
# 【块 25 修掉的第四个真缺陷 —— awk 的 %.2f 与链/Java 的 HALF_UP 在「恰好 .xx5」时差 0.01】
#   awk 的 printf "%.2f" 走的是 C 库的 double 舍入；
#   链（work/chain-truths.py 的 derive_sizes，Python Decimal + ROUND_HALF_UP）
#   与 Java（com.litemask.pack.SizeGate，BigDecimal + HALF_UP）走的是**精确十进制 HALF_UP**。
#   实测：131,072 B = 0.125 MiB → awk 打 0.12，链与 Java 打 0.13。
#   而且方向**依赖二进制表示**（5,000 B 那档又一致）→ 数据依赖，最难发现的一类。
#   → 一旦哪一层的字节数落在 .xx5 尾数上，链就会报「体积口径不一致」，
#     而真因在这个脚本自己身上；Windows 侧（Java）也会与 macOS 侧对不上。
#   → 改成**整数运算**：n = bytes*100，q = n/den，r = n%den，r*2 >= den 时 q+1。
#     分母是 2^20 与 10^6，整除与余数可精确求出 → 与 HALF_UP 逐位一致。
q2_scaled() {   # $1=字节数 $2=分母 → 打印「整数部分.两位小数」
  local n=$(( $1 * 100 ))
  local q=$(( n / $2 ))
  local r=$(( n % $2 ))
  if [ $(( r * 2 )) -ge "$2" ]; then q=$(( q + 1 )); fi
  printf '%d.%02d' $(( q / 100 )) $(( q % 100 ))
}
mib() { q2_scaled "$1" 1048576; }
mb()  { q2_scaled "$1" 1000000; }

# 余量：q2(budget - total/1048576)
#   与链/Java 同口径 —— 在**精确值**上相减，再**一次性**舍入
#   （不是「先把 total 舍入成 MiB 再减」，那会差 0.01）。
#   负数按 HALF_UP 的「远离零」处理（q2 对负数是 -0.005 → -0.01）。
slack_mib() {   # $1=预算 MiB  $2=总字节
  local s=$(( $1 * 1048576 - $2 ))
  local sign=""
  if [ "$s" -lt 0 ]; then sign="-"; s=$(( -s )); fi
  local n=$(( s * 100 ))
  local q=$(( n / 1048576 ))
  local r=$(( n % 1048576 ))
  if [ $(( r * 2 )) -ge 1048576 ]; then q=$(( q + 1 )); fi
  printf '%s%d.%02d' "$sign" $(( q / 100 )) $(( q % 100 ))
}

fail=0
rows=()
total=0

# 目录大小必须按**真实文件字节**累加，不能用 `du -sk`。
#
# `du` 报的是磁盘占用，每个文件至少占一个块（macOS APFS 常见 4KB），
# 于是结果依赖块大小——换台机器、换个文件系统，同一个产物量出不同的数字，
# 报告就没法复现，闸门也会在某台机器上误报。
#
# 本机实测（work/jlink-probe，88,068,060 B 的产物）：
#   find+stat = 88,068,060 B   du -sk = 88,379,392 B   差 0.30 MiB
# 这个差值本身不大，但它的**大小随文件数与块大小变化**，不可预测——
# 而预算判定要的是可复现的数字。jlink 运行时里几千个小文件时差距会明显放大。
#
# macOS 的 stat 是 -f%z，Linux 是 -c%s，先探测一次。
if stat -f%z /dev/null >/dev/null 2>&1; then
  STAT_FMT=(-f%z)
else
  STAT_FMT=(-c%s)
fi

dir_size() {
  find "$1" -type f -exec stat "${STAT_FMT[@]}" {} + 2>/dev/null \
    | awk '{s += $1} END {print s + 0}'
}

# ---- L0：jlink 运行时 ----
#
# ⚠️ 默认路径必须与 tools/package-macos.sh 的 RT 完全一致（"$ROOT/target/runtime-image"）。
# 这里曾经写成 target/runtime，于是闸门量到一个 MISSING 的目录并报「合计不成立」——
# **一个从不报警的闸门比没有闸门更糟**，因为报告会显得已经量过了。
# 体积是唯一「涨了没人发现」的东西，所以这个路径必须与产出它的脚本同源。
RUNTIME="${RUNTIME:-$ROOT/target/runtime-image}"
if [ -d "$RUNTIME" ]; then
  r=$(dir_size "$RUNTIME")
  rows+=("L0 $(layer_name L0)|$RUNTIME|$r")
  total=$((total + r))
else
  rows+=("L0 $(layer_name L0)|$RUNTIME|MISSING（打包产物在别处？可用 RUNTIME=<dir> 覆盖）")
  fail=1
fi

# ---- L1：fat jar ----
JAR="${JAR:-$ROOT/target/desens-core.jar}"
if [ -f "$JAR" ]; then
  j=$(wc -c < "$JAR" | tr -d ' ')
  rows+=("L1 $(layer_name L1)|$JAR|$j")
  total=$((total + j))
else
  rows+=("L1 $(layer_name L1)|$JAR|MISSING（可用 JAR=<file> 覆盖）")
  fail=1
fi

# ---- L2：模型包 ----
#
# 打包脚本把模型装在 app 内的 models/ner（方案 B：.pkg 不带模型，
# 但 app-image 与用户安装后的 ~/.litemask/models/ner 会有）。
# 找不到时回退到用户侧投放目录——**那才是用户实际会有的形态**，
# 量一个开发目录而不量用户目录，报出来的余量是虚的。
MODEL="${MODEL:-}"
if [ -z "$MODEL" ]; then
  for cand in "$ROOT/target/app-image/LightMeter.app/Contents/app/models/ner" \
              "$HOME/.litemask/models/ner"; do
    if [ -d "$cand" ]; then MODEL="$cand"; break; fi
  done
fi
MODEL="${MODEL:-$ROOT/target/app-image/LightMeter.app/Contents/app/models/ner}"
if [ -d "$MODEL" ]; then
  m=$(dir_size "$MODEL")
  rows+=("L2 $(layer_name L2)|$MODEL|$m")
  total=$((total + m))
else
  # 模型包是可选的（没装模型时工具仍然工作，只是脱不了人名），
  # 所以缺失**不算失败**，但必须显式打出来——
  # 一次体积报告里默默少了 7MB，读的人会以为「模型是免费的」。
  rows+=("L2 $(layer_name L2)|$MODEL|0（未找到；可选组件）")
fi

printf '%-22s %-46s %12s %10s %10s\n' 层 路径 字节 MiB MB
for r in "${rows[@]}"; do
  IFS='|' read -r name path bytes <<< "$r"
  case "$bytes" in
    ''|*[!0-9]*) printf '%-22s %-46s %12s\n' "$name" "$path" "$bytes" ;;
    *) printf '%-22s %-46s %12s %10s %10s\n' "$name" "$path" "$bytes" "$(mib "$bytes")" "$(mb "$bytes")" ;;
  esac
done

printf '%-22s %-46s %12s %10s %10s\n' "合计" "" "$total" "$(mib "$total")" "$(mb "$total")"
# 预算行原来打的是「94371840.00 MiB」——那是 90×1048576 的**字节数**，
# 被当成了 MiB 打印，等于把预算放大了一百万倍却看不出来。
# 两边都列，且标签写清楚是哪个口径。
printf '预算 %s MB = %s MiB = %s 字节（比较用 MiB）\n' \
  "$BUDGET_MB" \
  "$(q2_scaled $(( BUDGET_MIB * 1048576 )) 1048576)" \
  "$(( BUDGET_MIB * 1048576 ))"

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "X 有必需组件缺失（jlink 运行时或 fat jar），这次量出来的合计不成立"
  exit 1
fi

# 比较用 MiB：预算本身就是按 MiB 口径定的。
#
# 【块 25 修掉的第二个真缺陷 —— 比的是「舍入后的 MiB」】
#   原来比的是 total_mib（**四舍五入后**的两位小数）：
#   90.0044 MiB 舍成 90.00 → 判「在预算内」→ **放过 0.005 MiB 以内的超额**。
#   而链的解析器（work/chain-truths.py 的 derive_sizes）用的是**精确**比较
#   → 会出现「闸门放过了，链却报超预算」这种自相矛盾，
#   而报错指向的是「体积口径不一致」，真因在闸门自己身上。
#   → 闸门改成按**字节**比：与链同口径，也不用等舍入。
total_mib=$(mib "$total")
if [ "$total" -gt "$BUDGET_BYTES" ]; then
  echo ""
  echo "X 超出预算：${total_mib} MiB > ${BUDGET_MB} MB"
  echo "  对照原版元典脱敏 2.2.10：pkg 820MB / exe 959MB"
  exit 1
fi

echo ""
# 【块 25 修掉的第三个真缺陷 —— 余量是从「舍入后的 MiB」再减的】
#   原来：90 - 75.38（显示值）= 14.62
#   精确：90 - 75.3849… = 14.63
#   链的解析器按**精确值**核对「链日志里余量印的是 …」
#   → 差 0.01 就报红，而报错说的是「余量与预算对不上」，真因在闸门自己身上。
#   → 改成从**字节数**精确算。
echo "PASS 在预算内：${total_mib} MiB（余量 $(slack_mib "$BUDGET_MIB" "$total") MiB）"
echo ""
echo "未计入本闸门的（已知缺口，不要当成已经量过）："
echo "  - Windows 侧 jlink 产物：win-x64 的模块集与 .dll 体积**仍未实测**（本机量不到 Windows 运行时）；
    但 Windows 侧**自己的**打包脚本已接上同一套闸门（tools/package-windows.ps1），
    超预算或必需组件缺失时它会拒绝出包"
echo "  - 安装器自身（.msi/.pkg 框架与压缩后体积）"
