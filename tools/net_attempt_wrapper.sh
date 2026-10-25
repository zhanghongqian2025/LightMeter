#!/usr/bin/env bash
# 负控制用的「会联网的产品启动器」。
#
# 【它模拟什么】
#   模拟「产品进程在脱敏过程中发起了一次出站网络连接」。
#   网络可用 → 连上 → 继续执行真 java（端到端应当**全绿**）
#   网络被封 → 连不上 → 立刻非 0 退出（端到端应当**变红**）
#
# 【为什么它必须能「两种情况下表现不同」】
# 如果这个 wrapper 恒定失败，那么「沙箱内端到端变红」就可能只是
# 「wrapper 把流程搞坏了」，而不是「端到端对网络访问敏感」。
# 所以负控制是**差分式**的：同一个 wrapper、同一套端到端，
# 只差「封不封网」这一个变量，结果必须从绿翻红。
# 两次都由 tools/e2e-zero-egress.sh 跑。
#
# 依赖的环境变量：
#   NET_PROBE  tools/net_probe.py 的路径
#   NET_PORT   本机监听口端口（由 e2e-zero-egress.sh 起）
#   REAL_JAVA  真 java 的路径
set -uo pipefail

if [ -z "${NET_PROBE:-}" ] || [ -z "${NET_PORT:-}" ] || [ -z "${REAL_JAVA:-}" ]; then
  echo "负控制启动器缺少环境变量：NET_PROBE / NET_PORT / REAL_JAVA" >&2
  exit 64
fi

python3 "$NET_PROBE" connect 127.0.0.1 "$NET_PORT" >/dev/null 2>&1 || exit 42
exec "$REAL_JAVA" "$@"
