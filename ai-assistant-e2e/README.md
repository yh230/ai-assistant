# ai-assistant-e2e

AI 医院助手 V1.0 的 API 级 E2E 回归套件（Python + pytest + httpx）。

把项目手工 E2E 验收清单中可 API 断言的部分自动化：
**全黑盒**——只经网关 `http://localhost:8080` 调用（与前端同路径），不直连数据库、不 mock 服务、不改动任何生产代码。

## 前置条件

1. 服务已启动：Nacos → 六进程（`scripts/start-services.bat`）；前端非必需。
2. `.env` 已填写：复制 `.env.example` 为 `.env`，填入两家医院的员工账号与医院名。
   - 员工账号权限要求：可访问管理端（知识库 / AI 问诊记录），并具备 `hospital:manage`（科室列表）、`registration:schedule:manage`（号源池/排班）与 `registration:register:list`（挂号记录）权限——建议直接使用医院管理员角色账号。
3. `DEEPSEEK_API_KEY` 已配置（ai 组用例依赖真实大模型；未配置时用 `-m "not ai and not slow"` 只跑确定性子集）。
4. 环境数据：两家演示医院有科室数据；明日（T+1）存在可约排班——preflight 会自动校验并给出缺失提示。
5. **上传大小前提（#15）**：为验证「>2MB 被拒绝并提示 2MB 文案」，部署需将 `spring.servlet.multipart.max-file-size`（默认 1MB）调至 ≥3MB（Nacos 或服务 yml，属后端配置）；未调整时 >2MB 请求由框架层拒绝（消息为通用「系统繁忙」，不含「2MB」），#15 将如实失败——属环境前提未满足，非套件缺陷。
6. **短信验证码前提（手机号/扫码登录用例）**：Nacos `sms.provider=console` 且配置万能码 `sms.master-code`（样例 `123456`）——开发期控制台降级，无需真实短信即可用万能码登录。员工账号验证码登录要求账号已绑定手机号，套件在运行时经 `/api/profile` 为员工账号绑定随机手机号，测试后该联系方式保留。

## 快速开始

```bat
scripts\run-e2e.bat
```

流程：前置自检（缺什么列什么）→ 首次自动建 venv 并装依赖 → 执行 pytest → JUnit 报告落 `reports/` → 按配置推送结果（见「结果推送」节）。

## 常用姿势

| 场景 | 命令 |
|---|---|
| 全量（默认跳过 5 分钟超时用例） | `scripts\run-e2e.bat` |
| 离线快跑（不依赖 LLM） | `scripts\run-e2e.bat -m "not ai and not slow"` |
| 仅跑慢用例（#13，约 6 分钟） | `scripts\run-e2e.bat -m "slow"` |
| 单文件 / 单用例 | `scripts\run-e2e.bat tests/test_04_rag.py -v` |

> 注意：命令行 `-m` 会整体覆盖默认的 `not slow`，组合标记时记得带上 `and not slow`。

## 结果推送（企微 / 飞书 / 邮箱）

`run-e2e.bat` 在 pytest 结束后自动按 `.env` 配置推送结果（preflight 中止不推；单通道失败仅告警，不影响测试退出码）。也可随时手动重发：

```bat
cd ai-assistant-e2e
.venv\Scripts\python.exe notify.py reports\e2e-results.xml
```

配置项（`.env`，密钥不入库）：

| 变量 | 说明 |
|---|---|
| `E2E_NOTIFY_CHANNELS` | 启用通道，逗号分隔：`wecom,feishu,email`；留空 = 不推送 |
| `E2E_WECOM_WEBHOOK` | 企业微信群机器人 webhook 地址 |
| `E2E_FEISHU_WEBHOOK` | 飞书群机器人 webhook 地址 |
| `E2E_SMTP_HOST/PORT/USER/PASSWORD/FROM/TO` | SMTP（SSL）；FROM 留空则用 USER；TO 逗号分隔多个收件人 |

推送内容：数量摘要（失败/通过/跳过/耗时）+ 失败用例清单；邮件另附 JUnit 报告文件。新增通道：实现 `helpers/notify.py` 的 `Notifier` 抽象类并在 `build_notifiers` 注册即可。

## 24 项清单映射

| 清单# | 内容 | 用例 | 状态 |
|---|---|---|---|
| 1 | 患者注册→登录 | test_01_identity.py | 自动（浏览器 localStorage 属前端行为，不在本套件） |
| 2 | 患者 token 调员工接口 403 | test_01_identity.py | 自动 |
| 3 | 员工 token 调患者接口 403 | test_01_identity.py | 自动 |
| 4 | 登出/踢下线旧 token 失效 | test_01_identity.py | 自动 |
| 5 | 导诊推荐真实科室 | test_02_triage.py | 自动（ai） |
| 6 | 免责文案必现且落历史 | test_02_triage.py | 自动（ai） |
| 7 | 主动询问号源 | test_02_triage.py | 自动（ai，宽松关键词） |
| 8 | 问诊记录按医院可见 | test_02_triage.py | 自动（ai） |
| 9 | 预约先弹确认 | test_03_registration.py | 自动（ai） |
| 10 | 确认成功 + 余量 -1 + 记录可查 | test_03_registration.py | 自动（ai） |
| 11 | 重复预约拦截 | test_03_registration.py | 自动（ai，宽松关键词） |
| 12 | 取消确认零业务变化 | test_03_registration.py | 自动（ai） |
| 13 | 5 分钟确认超时 | test_03_registration.py | slow：默认跳过，`-m slow` 触发 |
| 14 | 停 registration-service 容错 | — | **手工保留**（需人为停服） |
| 15 | 上传/列表/三类拒绝 | test_04_rag.py | 自动 |
| 16 | 命中文档问答 | test_04_rag.py | 自动（ai） |
| 17 | 跨院知识隔离 | test_04_rag.py | 自动（ai） |
| 18 | 删除后命中消失 | test_04_rag.py | 自动（ai） |
| 19 | 限流 429 + 响应头 | test_05_safety.py | 自动 |
| 20 | 输入风控拦截（不调模型） | test_05_safety.py | 自动 |
| 21 | SSE 断开保留部分内容 | test_05_safety.py | 部分自动（ai）：历史保留断言自动；服务日志断言**手工保留** |
| 22 | 错误 KEY 降级 | — | **手工保留**（需改配置并重启） |
| 23 | 患者会话隔离 | test_06_isolation.py | 自动 |
| 24 | 员工会话隔离 | test_06_isolation.py | 自动 |

## 登录方式补充（手机号验证码 / 扫码）

| 场景 | 用例 | 说明 |
|---|---|---|
| 验证码登录（患者 / 员工，万能码） | test_sms_qr_login.py | 前置见「前置条件 6」；员工账号运行时绑定手机号 |
| 扫码登录全链路（create → confirm → poll） | test_sms_qr_login.py | 未确认 `WAITING`、确认后 `CONFIRMED` + 令牌、二次 poll `EXPIRED` |
| 连续错误验证码锁定 | test_sms_qr_login.py | 用随机未注册手机号，不锁死共享账号 |

Run: `scripts\run-e2e.bat tests\test_sms_qr_login.py -v`（或 `-k "sms or qr"`）

## 租户校验（P0-A）

AI 发起的预约挂号由挂号服务端做租户强校验：排班所属医院必须等于患者所属医院。

- **无法通过产品界面构造跨院预约**：导诊工具（科室/号源）按登录患者医院作用域返回，模型不会得到他院 `doctorUserId`；因此跨院拒绝以**单元测试**覆盖（`RegistrationServiceImplTenantCheckTest`）。
- **回归**：既有 `test_03_registration.py`（导诊→预约→确认）必须保持通过，验证同院正常链路未受影响。
- 匿名自助预约（`/api/registration/appointments`）不校验，行为不变。

## AI 角色库化（员工/患者双域）

- 员工角色：`GET /api/roles/admin?scope=STAFF`（全局）。
- 患者角色：`GET /api/roles/admin?scope=PATIENT&hospitalId=<id>`；已绑院账号强制使用本人医院；未绑院管理员（如平台管理员）必须显式传 `hospitalId`，管理端页面提供医院选择器。
- 权限：`ai:role:manage`（system-service 启动时幂等播种）；缺失时普通员工返回 403。
- 患者角色由管理端患者列表幂等补齐三类默认人设（`pat_<hospitalId>_<CHAT|TRIAGE|RAG>`），首次打开列表即可见；患者聊天时也会按需惰性建库。Nacos `ai.prompt.*` 仅作模板与兜底。

> 用例见 `tests/test_07_ai_roles.py`（无需 LLM，随默认套件执行）。患者域用例直接 GET 列表断言三类默认角色；提示词更新用例写回后会在 `finally` 中还原原提示词（仅在成功捕获原提示词时执行）。

## MCP SSE 端到端

`tests/test_09_mcp_schedule.py` 覆盖 MCP 真实 SSE 链路：建流、`initialize`、`tools/list`、`list_hospitals`、业务错误 `isError=true`、无效/缺失 Key 401，以及一条已排班数据查询。

前置：

1. 启动完整栈（含 `mcp-server`）并通过网关访问。
2. 在 Nacos `mcp-shared.yaml` 配置测试 Key 的 SHA-256 与医院 ID。
3. 在 `.env` 配置：

```text
MCP_E2E_API_KEY=mcp_xxx
# 可选，默认复用 E2E_BASE_URL
MCP_E2E_BASE_URL=http://localhost:8080
# 可选，限定医院；为空时使用 Key 返回的第一家医院
MCP_E2E_HOSPITAL_ID=1
```

运行：

```bash
pytest -m mcp -v
```

未配置 `MCP_E2E_API_KEY` 时用例自动跳过；查询已排班数据用例在库里没有可用排班时跳过。

## 不覆盖范围

- 前端浏览器行为（localStorage、确认弹窗 UI、停止按钮）与 Playwright UI 测试
- 后端服务级集成测试（@SpringBootTest）、前端 Vitest、CI 流水线
- 性能压测（另行计划）

## 目录说明

```
ai-assistant-e2e/
├── preflight.py          # 跑前自检（7 项：网关/医院/登录/知识库/科室/挂号权限/排班）
├── notify.py             # 结果推送入口（企微/飞书/邮件，可选）
├── conftest.py           # 全局 fixtures（账号、医院、科室、患者工厂）
├── helpers/              # api.py / sse.py / notify.py（推送抽象与三通道）；tests/ 为纯单测
├── assets/sample_doc.md  # RAG 上传样例（运行时注入唯一标记）
├── tests/                # 清单各节的 E2E 用例（含 test_sms_qr_login.py 登录方式补充）
└── reports/              # JUnit 报告（不入库）
```

## 密钥与入库约定

- `.env`（真实账号密码）不入库；`.env.example` 入库
- `.venv/`、`reports/`、`.pytest_cache/` 不入库

## 可运行性补充

- 含 ai 用例的全量运行约 5-8 分钟（不含 #13）；挂号用例会在 dev 库留下测试挂号记录（无公开清理接口，可接受）
- RAG 测试文档自建自清（异常中断时 teardown 兜底按 `e2e_E2E-DOC-` 前缀清理）
