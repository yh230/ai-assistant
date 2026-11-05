#!/usr/bin/env bash
# 启动 Kafka（KRaft 单机，macOS / Linux）：探测 KAFKA_HOME 或脚本目录 kafka_* 子目录
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KH="${KAFKA_HOME:-}"
if [[ -z "$KH" ]]; then
    for dir in "$SCRIPT_DIR"/kafka_*; do
        if [[ -d "$dir" ]]; then
            KH="$dir"
            break
        fi
    done
fi

if [[ -z "$KH" || ! -x "$KH/bin/kafka-server-start.sh" ]]; then
    echo "Kafka not found. Download 3.x+ from https://kafka.apache.org/downloads"
    echo "Unzip here as kafka_2.13-3.x.y or set KAFKA_HOME."
    exit 1
fi

CONFIG="$KH/config/kraft/server.properties"
LOG_DIR="${KAFKA_LOG_DIR:-/tmp/kraft-combined-logs}"
# 自定义 log.dirs 时需手动执行 kafka-storage format
if [[ ! -f "$LOG_DIR/meta.properties" ]]; then
    echo "kafka storage format required once..."
    UUID="$("$KH/bin/kafka-storage.sh" random-uuid)"
    "$KH/bin/kafka-storage.sh" format -t "$UUID" -c "$CONFIG"
fi

exec "$KH/bin/kafka-server-start.sh" "$CONFIG"
