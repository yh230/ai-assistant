@echo off
rem AI 医院助手 E2E 一键入口（前置：服务已手动启动、.env 已填写）
rem 用法：run-e2e.bat [pytest 参数...]   例：run-e2e.bat -m "not ai and not slow"
setlocal
cd /d "%~dp0..\ai-assistant-e2e"

if not exist "pytest.ini" (
    echo [E2E] 未找到 ai-assistant-e2e 目录，请确认仓库结构完整。
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo [E2E] 首次运行：创建虚拟环境...
    python -m venv .venv || (echo [E2E] venv 创建失败：请确认已安装 Python 3.10+ 且在 PATH 中。 & exit /b 1)
)

".venv\Scripts\python.exe" -m pip install -r requirements.txt -q || (echo [E2E] 依赖安装失败。 & exit /b 1)

echo [E2E] 前置自检...
".venv\Scripts\python.exe" preflight.py || (echo [E2E] 前置自检未通过，已中止（详见上方检查项）。 & exit /b 1)

if not exist reports mkdir reports
echo [E2E] 执行测试...
".venv\Scripts\python.exe" -m pytest --junitxml=reports\e2e-results.xml %*
set E2E_RC=%errorlevel%
echo [E2E] 推送测试结果（未配置 E2E_NOTIFY_CHANNELS 时自动跳过）...
if exist "reports\e2e-results.xml" ".venv\Scripts\python.exe" notify.py "reports\e2e-results.xml"
exit /b %E2E_RC%
