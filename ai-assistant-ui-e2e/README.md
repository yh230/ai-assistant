# ai-assistant-ui-e2e

AI 医院助手 UI 自动化测试（Playwright + pytest），覆盖登录、菜单权限、患者注册、AI 导诊、挂号确认、知识库与 390px 窄屏。

## 覆盖范围

| 用例 | 断言重点 | 依赖 LLM |
|------|----------|----------|
| 员工登录 + 菜单权限 | 登录成功、菜单按角色显示/隐藏 | 否 |
| 患者注册 → 登录 → 对话页 | 注册成功、进入患者端、会话列表可见 | 否 |
| 患者 AI 导诊 | SSE 内容渲染、免责文案出现 | 是 |
| 智能挂号确认 | 确认弹窗出现、确认后预约记录可见 | 是 |
| 知识库上传/列表/删除 | 上传成功、列表出现、删除后消失 | 否 |
| 患者端 390px | `scrollWidth <= innerWidth`，无横向溢出 | 否 |

不覆盖：全量管理端 CRUD、跨浏览器矩阵、像素级视觉回归、性能压测。

## 前置条件

- 前端已启动：`http://localhost:5173`
- 后端网关已启动：`http://localhost:8080`
- 两家演示医院、T+1 可约排班已存在（患者端选院与挂号用例依赖）
- 必需账号：`admin` 与 `staff1`（`staff1` 须绑定医院 1 且拥有 `ai:knowledge:manage`）；`staff2` 当前未使用
- 复用 `ai-assistant-e2e/.env` 的账号，填入本目录 `.env`

## 安装

```bash
cd ai-assistant-ui-e2e
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m playwright install chromium
cp .env.example .env
```

macOS/Linux 使用 `.venv/bin/python`。

## 配置

环境变量写入 `.env`，字段见 `.env.example`：

| 变量 | 说明 |
| --- | --- |
| `UI_E2E_BASE_URL` | 前端地址，默认 `http://localhost:5173` |
| `UI_E2E_API_BASE` | 网关地址，默认 `http://localhost:8080` |
| `UI_E2E_ADMIN_USER` / `UI_E2E_ADMIN_PASSWORD` | 平台管理员账号 |
| `UI_E2E_STAFF1_USER` / `UI_E2E_STAFF1_PASSWORD` | 医院 1 员工账号（必需，知识库用例依赖） |
| `UI_E2E_STAFF2_USER` / `UI_E2E_STAFF2_PASSWORD` | 医院 2 员工账号（当前未使用） |
| `UI_E2E_HOSPITAL1_NAME` / `UI_E2E_HOSPITAL2_NAME` | 两家演示医院名称（须与公开医院列表一致） |
| `UI_E2E_AI` | `1` 时运行 AI 用例，默认 `0` 跳过；在 `.env` 中设置 |
| `UI_E2E_FAST_TIMEOUT_MS` / `UI_E2E_LLM_TIMEOUT_MS` | 普通交互与模型响应的等待上限 |

`admin` 与 `staff1` 为必需账号，preflight 会校验两者登录；未配置时相关用例会 `skip` 或 preflight 失败。

## 前置检查

启动服务后先跑 preflight，确认前端、网关、账号、医院与 T+1 排班就绪：

```bash
.venv/Scripts/python.exe preflight.py
```

全部输出 `[OK  ]` 且退出码为 0 后再跑用例；有 `[FAIL]` 时按提示修复环境。

## 运行

```bash
# 确定性套件（默认跳过 ai/slow）
.venv/Scripts/python.exe -m pytest

# 只跑移动端
.venv/Scripts/python.exe -m pytest -m mobile

# AI 用例（需要后端模型可用；先在 .env 中设置 UI_E2E_AI=1）
.venv/Scripts/python.exe -m pytest -m ai

# 仅收集用例，检查导入与 fixture 是否正常
.venv/Scripts/python.exe -m pytest --collect-only -q
```

默认参数已包含 Chromium、失败保留 trace/截图/视频、JUnit 报告与 `-m "not ai and not slow"`；需要跑全部用例时用 `-m ""` 覆盖。

## 目录

```
ai-assistant-ui-e2e/
├── config.py          # .env 读取与默认值
├── api_client.py      # 网关 API 客户端（登录、造数、清理）
├── conftest.py        # 页面/会话/患者账号等 fixtures
├── preflight.py       # 环境前置检查
├── pages/             # Page Object
├── tests/             # test_01_auth ... test_07_mobile
├── reports/           # JUnit 报告（忽略入库）
└── artifacts/         # trace/截图/视频（忽略入库）
```

## 失败诊断

- JUnit：`reports/ui-e2e-results.xml`
- trace / 截图 / 视频：`artifacts/`
- trace 可用 `playwright show-trace artifacts/<用例目录>/trace.zip` 回放

## 已知限制

- 只跑 Chromium；扫码/短信登录未纳入 MVP。
- 预约测试数据会残留在 dev 库，用例只断言本次创建的记录。
- 当前没有 nightly 环境，AI 用例需在 `.env` 中设置 `UI_E2E_AI=1`。
- 暂不依赖 Docker Compose，需手动启动六进程。
- 不直连数据库，造数/清理走网关 API。
- 除固定免责声明外，只做结构化断言（消息内容节点、确认卡片），不比对具体文案。
