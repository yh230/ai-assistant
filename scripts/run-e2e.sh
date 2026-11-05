#!/usr/bin/env bash
# API E2E 一键入口（macOS / Linux）：前置服务已启动、ai-assistant-tests/.env 已填写
# 用法：scripts/run-e2e.sh [pytest 参数...]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TESTS_DIR="$ROOT_DIR/ai-assistant-tests"
PY="$ROOT_DIR/.venv/bin/python"

if [[ ! -f "$TESTS_DIR/api/pytest.ini" ]]; then
    echo "[E2E] 未找到 ai-assistant-tests/api 目录，请确认仓库结构完整。" >&2
    exit 1
fi

if [[ ! -x "$PY" ]]; then
    echo "[E2E] 首次运行：创建仓库级虚拟环境..."
    "${PYTHON:-python3}" -m venv "$ROOT_DIR/.venv"
fi

"$PY" -m pip install -r "$TESTS_DIR/requirements.txt" -q

cd "$TESTS_DIR/api"
echo "[E2E] 前置自检..."
"$PY" preflight.py

mkdir -p reports
echo "[E2E] 执行测试..."
set +e
"$PY" -m pytest --junitxml=reports/e2e-results.xml "$@"
rc=$?
set -e

echo "[E2E] 推送测试结果（未配置 E2E_NOTIFY_CHANNELS 时自动跳过）..."
if [[ -f reports/e2e-results.xml ]]; then
    "$PY" notify.py reports/e2e-results.xml
fi
exit "$rc"
