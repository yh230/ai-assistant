#!/usr/bin/env bash
# 启动 Zipkin（macOS / Linux）：优先读 ZIPKIN_JAR 环境变量，其次脚本目录 zipkin-server-*.jar
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${ZIPKIN_JAR:-}"

if [[ -z "$JAR" ]]; then
    for file in "$SCRIPT_DIR"/zipkin-server-*.jar; do
        if [[ -f "$file" ]]; then
            JAR="$file"
            break
        fi
    done
fi

if [[ -z "$JAR" || ! -f "$JAR" ]]; then
    echo "zipkin-server jar not found."
    echo "Download it to this folder or set ZIPKIN_JAR, see: https://zipkin.io/pages/quickstart.html"
    exit 1
fi

exec java -jar "$JAR"
