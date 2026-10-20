#!/usr/bin/env bash
# 启动 Nacos 单机模式（控制台 http://localhost:8849，服务端口 8848）
set -euo pipefail

: "${JAVA_HOME:?请先设置 JAVA_HOME 指向 JDK 17}"
: "${NACOS_HOME:?请先设置 NACOS_HOME 指向 Nacos 3.x 安装目录}"

if [[ ! -x "$NACOS_HOME/bin/startup.sh" ]]; then
    echo "[ERROR] 未找到 $NACOS_HOME/bin/startup.sh，请检查 NACOS_HOME。" >&2
    exit 1
fi

cd "$NACOS_HOME/bin"
exec bash startup.sh -m standalone
