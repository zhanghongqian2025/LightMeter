#!/usr/bin/env bash
# 解析 JDK 与 Maven 的位置——**不写死任何一台机器的路径**。
#
# 【为什么要改】
#   这些脚本过去把 JDK/Maven 写死在旧机器的目录上：
#     /Users/<某台机器>/Documents/Codex项目/REA/work/tools/jdk/Contents/Home
#     /Users/<某台机器>/Documents/Codex项目/REA/work/tools/maven/bin/mvn
#   机器一换、目录一搬，这些脚本就在**第一行**就失败：
#   JAVA_HOME 指到不存在的目录 → mvn / jlink / jpackage 全部找不到，
#   而错误信息（"Could not find or load main class" / "command not found"）
#   离真因（路径写死了）很远，排查一次要绕很大一圈。
#
# 【解析顺序】环境变量 → 标准安装位置 → 项目内工具链 → 旧布局（还在就还能用）。
#   任何一步都取不到 → **明确报错并退出，不静默降级**：
#   带着空 JAVA_HOME 继续跑，产出的包会缺东西，而构建本身不报错——
#   这种「构建成功但东西不对」比直接失败更难查。
#
# 【用法】
#   ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
#   . "$ROOT/tools/toolchain.sh"      # 设置并导出 JAVA_HOME / MVN_BIN
#
#   导出：
#     JAVA_HOME  —— 完整 JDK 的 home 目录（含 bin/javac、bin/jlink、bin/jpackage）
#     MVN_BIN    —— mvn 可执行文件的**路径**（不含参数）

# ---- JDK ----
_resolve_java_home() {
  local candidate
  # 1) macOS 自带 /usr/libexec/java_home（装了 Command Line Tools 就有）
  if [ -x /usr/libexec/java_home ]; then
    candidate="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [ -n "${candidate}" ] && [ -x "${candidate}/bin/javac" ]; then
      printf '%s' "${candidate}"
      return 0
    fi
  fi
  # 2) 项目内自带的工具链
  for candidate in "${ROOT}/tools/jdk/Contents/Home" "${ROOT}/tools/jdk"; do
    if [ -x "${candidate}/bin/javac" ]; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  # 3) 旧布局：还在就还能用（只是不再写死）
  for candidate in "${ROOT}/../work/tools/jdk/Contents/Home" "${ROOT}/../work/tools/jdk"; do
    if [ -x "${candidate}/bin/javac" ]; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  # 4) Homebrew 与系统安装位置
  for candidate in \
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk \
    /usr/local/opt/openjdk \
    /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [ -x "${candidate}/bin/javac" ]; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  return 1
}

# ---- Maven ----
_resolve_mvn() {
  local candidate
  for candidate in "${ROOT}/tools/maven/bin/mvn" "${ROOT}/../work/tools/maven/bin/mvn"; do
    if [ -x "${candidate}" ]; then
      printf '%s' "${candidate}"
      return 0
    fi
  done
  if [ -n "${MAVEN_HOME:-}" ] && [ -x "${MAVEN_HOME}/bin/mvn" ]; then
    printf '%s' "${MAVEN_HOME}/bin/mvn"
    return 0
  fi
  if command -v mvn >/dev/null 2>&1; then
    command -v mvn
    return 0
  fi
  return 1
}

# ---- 应用解析结果 ----
# ROOT 由调用方设置（各脚本惯例是 `ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"`）。
# 这里兜底一次：万一调用方忘了设，报错信息里的路径不该是空的。
: "${ROOT:=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

if [ -z "${LITEMASK_BUILD_JAVA_HOME:-}" ]; then
  _LH_JAVA_HOME="$(_resolve_java_home)" || {
    echo "X 找不到可用的 JDK。" >&2
    echo "  解除办法（任选其一）：" >&2
    echo "    1) 安装完整 JDK：brew install openjdk@21" >&2
    echo "    2) 指定路径：export LITEMASK_BUILD_JAVA_HOME=<JDK 的 Contents/Home>" >&2
    echo "    3) 放进项目内：${ROOT}/tools/jdk/Contents/Home" >&2
    exit 1
  }
  export JAVA_HOME="${_LH_JAVA_HOME}"
else
  export JAVA_HOME="${LITEMASK_BUILD_JAVA_HOME}"
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

if [ -z "${LITEMASK_MVN:-}" ]; then
  MVN_BIN="$(_resolve_mvn)" || {
    echo "X 找不到可用的 Maven。" >&2
    echo "  解除办法（任选其一）：" >&2
    echo "    1) brew install maven" >&2
    echo "    2) 指定路径：export LITEMASK_MVN=<mvn 的绝对路径>" >&2
    echo "    3) 放进项目内：${ROOT}/tools/maven/bin/mvn" >&2
    exit 1
  }
else
  MVN_BIN="${LITEMASK_MVN}"
fi
export MVN_BIN
