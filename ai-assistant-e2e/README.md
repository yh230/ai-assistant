# ai-assistant-e2e

AI 医院助手 V1.0 的 API 级 E2E 回归套件（Python + pytest + httpx）。

把关键验收场景自动化：
**全黑盒**——只经网关 `http://localhost:8080` 调用（与前端同路径），不直连数据库、不 mock 服务、不改动任何生产代码。覆盖身份越权、AI 导诊、挂号二次确认、RAG 跨院隔离、限流/风控、会话隔离等场景。

## 前置条件

1. 服务已启动：Nacos → 六进程（`scripts/start-services.bat`）；前端非必需。
2. `.env` 已填写：复制 `.env.example` 为 `.env`，填入两家医院的员工账号与医院名。
   - 员工账号权限要求：可访问管理端（知识库 / AI 问诊记录），并具备 `hospital:manage`（科室列表）、`registration:schedule:manage`（号源池/排班）与 `registration:register:list`（挂号记录）权限——建议直接使用医院管理员角色账号。
3. `DEEPSEEK_API_KEY` 已配置（ai 组用例依赖真实大模型；未配置时用 `-m "not ai and not slow"` 只跑确定性子集）。
4. 环境数据：两家演示医院有科室数据；明日（T+1）存在可约排班——preflight 会自动校验并给出缺失提示。
5. **上传大小前提**：为验证「>2MB 被拒绝并提示 2MB 文案」，部署需将 `spring.servlet.multipart.max-file-size`（默认 1MB）调至 ≥3MB（Nacos 或服务 yml，属后端配置）；未调整时 >2MB 请求由框架层拒绝（消息为通用「系统繁忙」，不含「2MB」），该用例将如实失败——属环境前提未满足，非套件缺陷。
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
| 仅跑慢用例（确认超时，约 6 分钟） | `scripts\run-e2e.bat -m "slow"` |
| 单文件 / 单用例 | `scripts\run-e2e.bat tests/test_04_rag.py -v` |

> 注意：命令行 `-m` 会整体覆盖默认的 `not slow`，组合标记时记得带上 `and not slow`。

## 结果推送（可选）

`run-e2e.bat` 结束后按 `.env` 的 `E2E_NOTIFY_CHANNELS`（`wecom,feishu,email`）推送摘要与失败清单；密钥不入库，单通道失败只告警，不影响退出码。配置项见 `.env.example`。

## 登录方式补充（手机号验证码 / 扫码）

| 场景 | 用例 | 说明 |
|---|---|---|
| 验证码登录（患者 / 员工，万能码） | test_sms_qr_login.py | 前置见「前置条件 6」；员工账号运行时绑定手机号 |
| 扫码登录全链路（create → confirm → poll） | test_sms_qr_login.py | 未确认 `WAITING`、确认后 `CONFIRMED` + 令牌、二次 poll `EXPIRED` |
| 连续错误验证码锁定 | test_sms_qr_login.py | 用随机未注册手机号，不锁死共享账号 |

Run: `scripts\run-e2e.bat tests\test_sms_qr_login.py -v`（或 `-k "sms or qr"`）

## MCP SSE 端到端

`tests/test_09_mcp_schedule.py` 覆盖真实 SSE 链路：建流、`initialize`、`tools/list`、`list_hospitals`、业务错误 `isError=true`、无效/缺失 Key 401 与排班查询。

前置：完整栈（含 `mcp-server`）、Nacos `mcp-shared.yaml` 中的测试 Key，以及 `.env`：

```text
MCP_E2E_API_KEY=mcp_xxx
MCP_E2E_BASE_URL=http://localhost:8080   # 可选
MCP_E2E_HOSPITAL_ID=1                    # 可选
```

运行 `pytest -m mcp -v`；未配置 Key 或没有可用排班时自动跳过。接入与 Key 配置见 [docs/mcp.md](../docs/mcp.md)。

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
├── tests/                # 身份/AI/挂号/RAG/风控等 E2E 用例（含 test_sms_qr_login.py）
└── reports/              # JUnit 报告（不入库）
```

## 密钥与入库约定

- `.env`（真实账号密码）不入库；`.env.example` 入库
- `.venv/`、`reports/`、`.pytest_cache/` 不入库

## 可运行性补充

- 含 ai 用例的全量运行约 5-8 分钟（不含确认超时用例）；挂号用例会在 dev 库留下测试挂号记录（无公开清理接口，可接受）
- RAG 测试文档自建自清（异常中断时 teardown 兜底按 `e2e_E2E-DOC-` 前缀清理）
