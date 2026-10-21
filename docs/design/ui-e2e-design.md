# UI 自动化测试项目设计（Playwright + pytest）

> 状态：待评审  
> 日期：2026-10-20  
> 定位：在现有 API 级 E2E 之上，新增一层“薄 UI 冒烟测试”，覆盖浏览器渲染、路由守卫、Element Plus 交互与窄屏布局。

## 1. 背景与目标

现有 [ai-assistant-e2e](../../ai-assistant-e2e/README.md) 是 pytest + httpx 的 API 黑盒测试，覆盖业务逻辑、权限隔离、并发与 AI 评测，但不覆盖：

- 页面是否正确渲染、路由守卫是否生效；
- Element Plus 弹窗、表单、上传控件是否可操作；
- SSE 流式内容是否真正出现在页面上；
- 患者 H5 在窄屏下是否横向溢出。

本项目目标：

1. 用 Playwright 覆盖 6 条关键用户旅程；
2. 失败时能通过截图、trace、视频快速定位；
3. 与现有 API E2E 分工：API 管业务逻辑，UI 管渲染与交互；
4. 不引入第二套后端测试栈，不直连数据库。

非目标：

- 全量覆盖所有管理端 CRUD；
- 跨浏览器矩阵（MVP 只跑 Chromium）；
- 像素级视觉回归；
- 性能/压测；
- 替代现有 API E2E。

## 2. 范围与用例

### MVP（6 条）

| # | 用例 | 断言重点 | LLM |
|---|------|----------|-----|
| 1 | 员工登录 + 菜单权限 | 登录成功、侧栏菜单按角色显示/隐藏 | 否 |
| 2 | 患者注册 → 登录 → 对话页 | 注册成功、进入患者端、会话列表可见 | 否 |
| 3 | 患者 AI 导诊 | SSE token 逐步渲染、免责文案出现 | 是 |
| 4 | 智能挂号确认 | `confirm_required` 弹窗出现、确认后“我的预约”出现记录 | 是 |
| 5 | 知识库上传/列表/删除 | 上传 md 成功、列表出现、删除后消失 | 否 |
| 6 | 患者端 390px 布局 | `scrollWidth <= innerWidth`，登录/对话页无横向溢出 | 否 |

### P1（后续）

- 登出、刷新令牌、跨 tab 续期；
- 患者/员工路由守卫（直接访问对方路径被拒绝）；
- 短信验证码登录（console 万能码）；
- 扫码登录（PC + 手机双 context）；
- 排班/挂号记录列表的筛选与分页。

### 明确不做

- 精确断言 AI 回答文本；
- 限流、停服容错（继续由 API E2E 或手工覆盖）；
- 所有管理页面的增删改查全集。

## 3. 技术选型

**选定：Playwright + Python + pytest（同步 API）**

理由：

- 与现有 `ai-assistant-e2e` 共用 pytest、fixture、marker、报告习惯；
- Playwright 原生支持多浏览器、移动端模拟、多 context（扫码登录需要 PC + 手机）；
- trace / screenshot / video 对 UI 失败定位价值高；
- 比 Selenium 少显式等待，比 Cypress 更适合多 tab / 多 origin 场景。

备选：

| 方案 | 优点 | 不选原因 |
|------|------|----------|
| Playwright + TypeScript | 前端同学更熟悉，类型好 | 多一套技术栈，报告/环境/CI 需单独维护 |
| Selenium | 生态成熟 | 等待与 flaky 成本更高 |
| Cypress | 调试体验好 | 多 tab、扫码双端场景较弱 |

依赖：

```text
pytest
pytest-playwright
playwright
httpx
python-dotenv
pyyaml
pytest-timeout
```

首次安装需要 `playwright install chromium`。

## 4. 项目结构

```text
ai-assistant-ui-e2e/
├── conftest.py              # 浏览器、context、storageState、fixtures
├── pytest.ini               # marker、超时、默认排除 ai/slow
├── requirements.txt
├── .env.example
├── README.md
├── config.py                # 环境变量与超时
├── api_client.py            # 轻量 API 造数/清理（仅 httpx）
├── pages/
│   ├── base_page.py
│   ├── staff_login_page.py
│   ├── patient_login_page.py
│   ├── patient_chat_page.py
│   ├── knowledge_page.py
│   └── main_layout.py
├── fixtures/
│   ├── accounts.py
│   ├── storage_state.py
│   ├── data_factory.py
│   └── cleanup.py
├── tests/
│   ├── test_01_auth.py
│   ├── test_02_patient_journey.py
│   ├── test_03_ai_flows.py
│   ├── test_04_knowledge.py
│   ├── test_05_permissions.py
│   └── test_06_mobile.py
├── data/
│   ├── prompts.yaml
│   └── sample_doc.md
├── reports/                 # JUnit，gitignore
└── artifacts/               # trace/截图/视频，gitignore
```

### 组件职责

- `config.py`：读取 `UI_E2E_BASE_URL`、`UI_E2E_API_BASE`、账号与超时。
- `api_client.py`：只做造数与清理（登录、注册患者、上传/删除知识文档、查询预约），不直连数据库。
- `pages/`：Page Object，只暴露业务动作（`login()`、`send_message()`、`confirm_booking()`），选择器封装在内部。
- `fixtures/`：账号、登录态、随机数据、清理。
- `tests/`：只写旅程与断言，不写底层等待和选择器。

## 5. 登录态与 context 策略

前端登录态使用独立 localStorage：

| 身份 | token | user | refresh |
|------|-------|------|---------|
| 员工 | `ai_token` | `ai_user` | `ai_refresh_token` |
| 患者 | `patient_token` | `patient_user` | `patient_refresh_token` |

策略：

1. **API 登录 + storageState 注入**：fixture 通过 API 获取 token，写入 `storageState`，避免每个用例都操作登录表单；
2. **保留 1 个真正的 UI 登录用例**，验证表单、错误提示与跳转；
3. 员工/患者分别使用独立 context；
4. 扫码登录测试使用两个 context（PC 创建二维码、手机确认）；
5. 测试账号只从环境变量读取，不写死在代码里。

## 6. 测试钩子（前端改造）

当前前端没有 `data-testid`。建议统一命名为 `data-testid="<page>-<element>"`，只加在关键元素上，不改变行为。

| 页面 | 建议 testid |
|------|-------------|
| 员工登录 | `login-username`、`login-password`、`login-submit`、`login-error`、`login-tab-sms`、`login-tab-qr` |
| 患者登录/注册 | `patient-phone`、`patient-password`、`patient-login-submit`、`patient-register-name`、`patient-register-hospital`、`patient-register-submit`、`patient-error` |
| 主布局 | `app-menu`、`menu-<module>`、`user-menu`、`logout` |
| 患者对话 | `chat-input`、`chat-send`、`chat-stop`、`chat-message-user`、`chat-message-assistant`、`chat-session-list`、`confirm-dialog`、`confirm-ok`、`confirm-cancel`、`references-panel` |
| 知识库 | `knowledge-file-input`、`knowledge-upload`、`knowledge-row-<id>`、`knowledge-delete`、`knowledge-empty` |
| 挂号 | `appointment-row-<id>`、`appointment-status`、`appointment-cancel` |

## 7. 数据策略

- 账号：`.env` 中配置 `UI_E2E_ADMIN_*`、`UI_E2E_STAFF1_*`、`UI_E2E_STAFF2_*`，沿用 API E2E 的双医院账号思路。
- 患者：手机号随机生成，姓名前缀 `UIE2E-`，避免与真实/其他测试数据冲突。
- 知识库：文档名前缀 `ui-e2e-`，用例结束在 `finally` 中通过 API 删除。
- 挂号：优先在用例结束通过 API 取消；若没有公开清理接口，使用专用测试库或接受残留并在 README 标注。
- AI 会话：每个用例新建独立会话，避免历史污染。
- 不直连数据库；造数与清理都走网关 API。

## 8. AI 非确定性处理

AI 用例只做结构断言：

- 发送消息后出现 assistant 消息容器，且内容逐步增长；
- 免责文案可见；
- 需要写操作时出现 `confirm_required` 弹窗，包含操作摘要与确认/取消按钮；
- 确认后“我的预约”出现新记录。

不使用固定回答文本断言。AI 用例统一加 `@pytest.mark.ai`，默认不进入确定性门禁；无模型 Key 时 skip 并给出原因。允许对已知 AI flaky 用例做最多 1 次重试，但确定性用例禁止靠重试掩盖问题。

## 9. 运行方式与标记

```bash
# 确定性套件（默认，不含 AI/slow）
pytest

# 仅 AI 旅程
pytest -m ai

# 窄屏
pytest -m mobile

# 调试
pytest --headed --slowmo 200
PWDEBUG=1 pytest tests/test_01_auth.py
```

`pytest.ini` 建议：

```ini
[pytest]
markers =
    ui: UI 自动化用例
    ai: 依赖大模型输出的用例
    mobile: 移动端视口用例
    slow: 长等待用例
addopts = -m "not ai and not slow" -ra --strict-markers --tb=short
timeout = 180
```

## 10. 报告与失败诊断

- JUnit XML：`reports/ui-e2e-results.xml`；
- 失败时保留 Playwright trace：`artifacts/trace-<case>.zip`；
- 失败截图与视频：`artifacts/screenshots/`、`artifacts/videos/`；
- 失败时输出浏览器 console 与关键网络请求摘要（脱敏 token）；
- 可选：后续接入 Allure，但 MVP 不引入。

## 11. CI 与运行策略

MVP 阶段：

- 本地/测试环境手动运行；
- 默认确定性套件；
- AI 套件 nightly 或发布前运行；
- 不做 PR 必过门禁，避免环境不稳定拖慢开发。

后续（依赖 Docker Compose / lite profile）：

- GitHub Actions 增加 `ui-e2e-nightly`：启动完整栈 → seed → 跑确定性套件 → 上传 artifacts；
- 使用 headless Chromium；
- 串行执行，暂不启用 xdist，避免测试数据互相污染；
- AI 用例只在有模型 Key 的受信环境运行，fork PR 不跑。

## 12. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| LLM/SSE 时序不稳定 | AI 用例 flaky | 结构断言 + `ai` 标记 + 最多 1 次重试 |
| 测试数据污染 | 重复运行失败 | 随机患者、`ui-e2e-` 前缀、finally 清理、专用测试库 |
| 选择器随 UI 变化 | 维护成本高 | 关键元素加 `data-testid`，Page Object 收敛选择器 |
| 环境启动重 | 新人/CI 成本高 | Docker Compose / lite profile；preflight 检查 |
| 动画/异步加载 | 等待不稳定 | Playwright `expect` 自动等待、禁用动画、reduced motion |
| 扫码/短信依赖外部条件 | 用例难稳定 | 放 P1，不在 MVP 门禁内 |
| 浏览器依赖 | 安装成本 | MVP 只装 Chromium，文档写明 `playwright install chromium` |

## 13. 验收标准

1. 在已 seed 的本地环境，`pytest` 默认套件全部通过，且不依赖 LLM；
2. `pytest -m ai` 在有模型 Key 时通过，无 Key 时明确 skip；
3. 失败用例能产出 JUnit、截图和 trace；
4. 390px 用例断言 `document.documentElement.scrollWidth <= window.innerWidth`；
5. 全程不直连数据库，造数/清理只走 API；
6. 不修改生产业务逻辑，只增加 `data-testid` 与测试配置；
7. README 包含环境要求、账号配置、运行命令和已知限制。

## 14. 实施阶段（待 spec 通过后）

| 阶段 | 内容 |
|------|------|
| Phase 0 | 增加 `data-testid`；准备测试账号、seed/reset、preflight |
| Phase 1 | 搭建 Playwright 框架 + 3 条确定性用例（登录、知识库、窄屏） |
| Phase 2 | 患者旅程 + AI 结构断言 + 挂号确认 |
| Phase 3 | 报告、trace、nightly CI、README |

在 spec 评审通过前，不创建项目目录、不修改前端代码。

## 15. 待确认项

1. 是否有专用测试库，还是接受预约测试数据残留在 dev 库？
2. 测试账号是否复用现有 `ai-assistant-e2e` 的两院账号？
3. nightly 环境是否已有可用的 LLM Key？
4. Docker Compose / lite profile 是否排在 UI 项目之前？
5. 是否接受 MVP 只跑 Chromium、扫码/短信放 P1？
