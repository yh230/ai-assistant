#!/usr/bin/env bash
# 一键启动后端：五业务服务 + 网关（后台运行，日志写入 ai-assistant-backend/logs/）
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/ai-assistant-backend"

if [[ -x ./mvnw ]]; then
    MVN=./mvnw
elif command -v mvn >/dev/null 2>&1; then
    MVN=mvn
else
    echo "[ERROR] 未找到 Maven。请安装 Maven 3.9+，或保留仓库自带的 mvnw。" >&2
    exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "[ERROR] 未设置 JAVA_HOME，请指向 JDK 17。" >&2
    exit 1
fi

mkdir -p logs
modules=(services/auth-service services/system-service services/hospital-service \
         services/registration-service services/ai-chat-service services/mcp-server gateway)

for module in "${modules[@]}"; do
    name="${module##*/}"
    echo "Starting $name ..."
    nohup "$MVN" -pl "$module" -am spring-boot:run > "logs/$name.log" 2>&1 &
    echo $! > "logs/$name.pid"
done

echo "全部服务已在后台启动。"
echo "日志目录：$PWD/logs"
echo "停止服务：kill \$(cat $PWD/logs/*.pid)"
