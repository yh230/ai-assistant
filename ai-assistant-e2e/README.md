# ai-assistant-e2e

AI 医院助手 V1.0 的 API 级 E2E 回归套件（Python + pytest + httpx）。

把 `docs/superpowers/e2e/2026-10-13-ai-hospital-v1-e2e.md` 手工验收清单中可 API 断言的部分自动化：
**全黑盒**——只经网关 `http://localhost:8080` 调用（与前端同路径），不直连数据库、不 mock 服务、不改动任何生产代码。

## 前置条件

1. 服务已启动：Nacos → 六进程（`scripts/start-services.bat`）；前端非必需。
2. `.env` 已填写：复制 `.env.example` 为 `.env`，填入两家医院的员工账号与医院名。
   - 员工账号权限要求：可访问管理端（知识库 / AI 问诊记录），并具备 `hospital:manage`（科室列表）、`registration:schedule:manage`（号源池/排班）与 `registration:register:list`（挂号记录）权限——建议直接使用医院管理员角色账号。
3. `DEEPSEEK_API_KEY` 已配置（ai 组用例依赖真实大模型；未配置时用 `-m "not ai and not slow"` 只跑确定性子集）。
4. 环境数据：两家演示医院有科室数据；明日（T+1）存在可约排班——preflight 会自动校验并给出缺失提示。
5. **上传大小前提（#15）**：为验证「>2MB 被拒绝并提示 2MB 文案」，部署需将 `spring.servlet.multipart.max-file-size`（默认 1MB）调至 ≥3MB（Nacos 或服务 yml，属后端配置）；未调整时 >2MB 请求由框架层拒绝（消息为通用「系统繁忙」，不含「2MB」），#15 将如实失败——属环境前提未满足，非套件缺陷。

## 快速开始

```bat
scripts\run-e2e.bat
```

流程：前置自检（缺什么列什么）→ 首次自动建 venv 并装依赖 → 执行 pytest → JUnit 报告落 `reports/`。

## 常用姿势

| 场景 | 命令 |
|---|---|
| 全量（默认跳过 5 分钟超时用例） | `scripts\run-e2e.bat` |
| 离线快跑（不依赖 LLM） | `scripts\run-e2e.bat -m "not ai and not slow"` |
| 仅跑慢用例（#13，约 6 分钟） | `scripts\run-e2e.bat -m "slow"` |
| 单文件 / 单用例 | `scripts\run-e2e.bat tests/test_04_rag.py -v` |

> 注意：命令行 `-m` 会整体覆盖默认的 `not slow`，组合标记时记得带上 `and not slow`。

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

## 不覆盖范围

- 前端浏览器行为（localStorage、确认弹窗 UI、停止按钮）与 Playwright UI 测试
- 后端服务级集成测试（@SpringBootTest）、前端 Vitest、CI 流水线
- 性能压测（另行计划）

## 目录说明

```
ai-assistant-e2e/
├── preflight.py          # 跑前自检（6 项：网关/医院/登录/知识库/科室/排班）
├── conftest.py           # 全局 fixtures（账号、医院、科室、患者工厂）
├── helpers/              # api.py（HTTP 封装）、sse.py（SSE 解析）；tests/ 为纯单测
├── assets/sample_doc.md  # RAG 上传样例（运行时注入唯一标记）
├── tests/                # 六个清单节的 E2E 用例
└── reports/              # JUnit 报告（不入库）
```

## 密钥与入库约定

- `.env`（真实账号密码）不入库；`.env.example` 入库
- `.venv/`、`reports/`、`.pytest_cache/` 不入库

## 可运行性补充

- 含 ai 用例的全量运行约 5-8 分钟（不含 #13）；挂号用例会在 dev 库留下测试挂号记录（无公开清理接口，可接受）
- RAG 测试文档自建自清（异常中断时 teardown 兜底按 `e2e_E2E-DOC-` 前缀清理）
