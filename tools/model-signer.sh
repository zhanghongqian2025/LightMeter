#!/usr/bin/env bash
# 发布侧的模型包签名工具（不随程序分发）。
#
# 为什么要这个包装脚本：签名工具是**源码单文件**（tools/ModelPackageSigner.java），
# 而它用到 jackson（模型包签名信封是 JSON），jackson 在本地仓库里、不在 target/classes 里。
# 直接 `java -cp target/classes tools/ModelPackageSigner.java` 会报
# "package com.fasterxml.jackson.databind does not exist"。
#
# 所以这里把「产品类 + 依赖 jar」拼成一条完整 classpath。
#
# 依赖 classpath 是 mvn 离线现算的（dependency:build-classpath），缓存在 target/ 里；
# 缓存命中时**不联网、不重算**。
#
# 用法:
#   ./tools/model-signer.sh keygen key.json
#   ./tools/model-signer.sh pubkey key.json pub.pem
#   ./tools/model-signer.sh sign model.zip key.json
#   ./tools/model-signer.sh verify model.zip pub.pem
#   ./tools/model-signer.sh keyid pub.pem
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

# 工具链不进系统：JDK 与 Maven 都在项目外的 work/tools/ 里。
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME}" ]; then
  TOOLS="$(cd "${ROOT}/.." && pwd)/work/tools"
  if [ -d "${TOOLS}/jdk/Contents/Home" ]; then
    JAVA_HOME="${TOOLS}/jdk/Contents/Home"
  fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export JAVA_HOME
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

MVN="${MVN:-}"
if [ -z "${MVN}" ]; then
  TOOLS="$(cd "${ROOT}/.." && pwd)/work/tools"
  if [ -x "${TOOLS}/maven/bin/mvn" ]; then
    MVN="${TOOLS}/maven/bin/mvn"
  else
    MVN="$(command -v mvn)"
  fi
fi

CP_FILE="target/deps-classpath.txt"

# 产品类要先编译过：签名工具要调 ModelPackageSignature / ModelTrust。
if [ ! -d target/classes/com/litemask/ner ]; then
  echo "== 先编译产品代码 =="
  "${MVN}" -B -o -Dstyle.color=never compile
fi

# 依赖 classpath：缓存优先，缓存不存在才现算（离线）。
if [ ! -s "${CP_FILE}" ]; then
  echo "== 现算依赖 classpath（离线）=="
  "${MVN}" -B -o -q dependency:build-classpath -Dmdep.outputFile="${CP_FILE}"
fi

DEPS="$(cat "${CP_FILE}")"
CP="target/classes:${DEPS}"

exec java -cp "${CP}" tools/ModelPackageSigner.java "$@"
