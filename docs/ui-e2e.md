# UI 自动化测试（Playwright）

在 API 级 E2E 之上增加一层“薄 UI 冒烟测试”，覆盖浏览器渲染、路由守卫、Element Plus 交互和窄屏布局。

## 范围

MVP 覆盖 6 条关键旅程：

| 用例 | 断言重点 | 依赖 LLM |
|------|----------|----------|
| 员工登录 + 菜单权限 | 登录成功、菜单按角色显示/隐藏 | 否 |
| 患者注册 → 登录 → 对话页 | 注册成功、进入患者端、会话列表可见 | 否 |
| 患者 AI 导诊 | SSE 内容渲染、免责文案出现 | 是 |
| 智能挂号确认 | 确认弹窗出现、确认后预约记录可见 | 是 |
| 知识库上传/列表/删除 | 上传成功、列表出现、删除后消失 | 否 |
| 患者端 390px | `scrollWidth <= innerWidth`，无横向溢出 | 否 |

不覆盖：全量管理端 CRUD、跨浏览器矩阵、像素级视觉回归、性能压测。

## 技术选型

- Playwright + Python + pytest（同步 API）
- 仅 Chromium（MVP）
- API 造数 + UI 断言，不直连数据库
- 登录态用 `storageState` 复用
- 关键元素只新增 `data-testid`，不改业务逻辑

## 项目结构

```text
ai-assistant-ui-e2e/
├── conftest.py          # 浏览器、context、storageState、fixtures
├── pytest.ini           # marker、超时、默认排除 ai/slow
├── config.py            # 环境变量
├── pages/               # Page Object
├── tests/               # 6 条旅程
├── preflight.py         # 环境与账号预检
└── README.md            # 安装与运行说明
```

## 运行

```bash
cd ai-assistant-ui-e2e
python -m venv .venv
# Windows: .venv\Scripts\activate
pip install -r requirements.txt
playwright install chromium
cp .env.example .env
python preflight.py
pytest
```

AI 用例默认跳过；设置 `UI_E2E_AI=1` 后运行：

```bash
UI_E2E_AI=1 pytest -m ai
```

窄屏用例：

```bash
pytest -m mobile
```

## 配置

复制 `.env.example` 为 `.env`，配置：

- `UI_E2E_BASE_URL`：前端地址，默认 `http://localhost:5173`
- `UI_E2E_API_BASE`：网关地址，默认 `http://localhost:8080`
- `UI_E2E_ADMIN_*`：管理员账号
- `UI_E2E_STAFF1_*` / `UI_E2E_STAFF2_*`：两院员工账号
- `UI_E2E_HOSPITAL1_NAME` / `UI_E2E_HOSPITAL2_NAME`
- `UI_E2E_AI=1`：启用 AI 用例

## 失败诊断与 CI

- 默认保留 JUnit、截图、trace、视频（失败时）。
- 产物在 `artifacts/`，报告在 `reports/`。
- 默认串行执行，暂不启用 xdist，避免测试数据互相污染。
- 未纳入 PR 必过门禁；建议 nightly 或发布前在完整栈环境运行。

## 边界

- UI 用例验证“渲染与交互”，业务逻辑、权限隔离、并发仍由 `ai-assistant-e2e` 覆盖。
- 扫码/短信登录放 P1；MVP 不走外部短信和二维码依赖。
- 详细安装、账号前置与已知问题见 `ai-assistant-ui-e2e/README.md`。
- 历史设计与实施计划见 `docs/design/archive/ui-e2e-design.md`。
