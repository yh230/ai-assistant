# ai-assistant-tests

AI 医院助手黑盒测试套件，包含两个彼此独立的测试组：

| 组 | 技术栈 | 覆盖 | 文档 |
| --- | --- | --- | --- |
| `api/` | pytest + httpx | 身份越权、AI 导诊、挂号、RAG 隔离、安全加固、MCP SSE、AI 评测 | [api/README.md](api/README.md) |
| `ui/` | pytest + Playwright | 登录、菜单权限、患者注册、AI 导诊、挂号确认、知识库、390px 窄屏 | [ui/README.md](ui/README.md) |

两套用例均为全黑盒：只经网关/前端调用，不直连数据库、不 mock 服务、不改生产代码。

## 目录

```
ai-assistant-tests/
├── .env.example       # 两套用例共用的配置模板
├── requirements.txt   # 共用依赖（含可选的 Playwright）
├── api/               # API 级 E2E（原 ai-assistant-e2e）
│   ├── pytest.ini
│   ├── preflight.py
│   ├── helpers/       # api / sse / notify 与纯单测
│   ├── eval/          # AI 评测套件
│   └── tests/
└── ui/                # UI 自动化（原 ai-assistant-ui-e2e）
    ├── pytest.ini
    ├── preflight.py
    ├── pages/
    └── tests/
```

## 安装

在仓库根目录创建全仓共用的虚拟环境（data-platform 与两套测试共用）：

```bash
cd <仓库根目录>
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r ai-assistant-tests/requirements.txt
.venv/Scripts/python.exe -m playwright install chromium   # 仅 UI 套件需要
cp ai-assistant-tests/.env.example ai-assistant-tests/.env
```

macOS/Linux 使用 `.venv/bin/python`。需要同时运行 data-platform 时，在同一个环境中执行
`pip install -e ai-assistant-data-platform`。

## 配置

所有配置集中在 `ai-assistant-tests/.env`：

- API 套件读取 `E2E_*`；UI 套件读取 `UI_E2E_*`，账号与医院未设置时自动复用对应的 `E2E_*`。
- 两家医院的员工账号、医院名称、网关地址按 `.env.example` 填写；`admin/admin123` 为 Seeder 默认值。
- 真实账号密码不入库；`.env` 已被忽略。

## 运行

```bash
# API 套件：前置自检 + 测试（Windows）
scripts\run-e2e.bat
# macOS / Linux
scripts/run-e2e.sh

# 或手动执行
cd ai-assistant-tests/api
..\..\.venv\Scripts\python.exe preflight.py
..\..\.venv\Scripts\python.exe -m pytest

# UI 套件
cd ai-assistant-tests/ui
..\..\.venv\Scripts\python.exe preflight.py
..\..\.venv\Scripts\python.exe -m pytest
```

两套用例的默认 `addopts` 各自维护在 `api/pytest.ini`、`ui/pytest.ini`：API 默认跳过 `slow/eval`，UI 默认跳过 `ai/slow` 并使用 Chromium。具体开关与报告位置见各组 README。

## 说明

- `api/` 与 `ui/` 的 `pytest.ini` 保持独立，因为两组的默认参数（浏览器、trace、超时、报告路径）不同；共用的是环境、依赖与文档入口。
- `api/helpers/tests/` 下的纯单测不依赖运行中的服务，可在任意环境执行：
  `cd ai-assistant-tests/api && ..\..\.venv\Scripts\python.exe -m pytest helpers/tests -q`
- 完整前置条件（服务启动、账号权限、LLM Key、短信万能码、上传大小限制等）见 [api/README.md](api/README.md) 与 [ui/README.md](ui/README.md)。
