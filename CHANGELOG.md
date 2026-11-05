# Changelog

本文件记录项目的版本级变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [Unreleased]

### Added

- 开源门面与可复现基础：MIT License、Maven Wrapper、跨平台启动脚本、GitHub Actions CI（后端单测 + 前端构建/测试）。
- 数据治理与运营大屏（ai-assistant-data-platform）：7 张源表全量/增量抽取、Kafka ODS、ClickHouse ODS/DWD/ADS、质量规则、告警 Webhook 与 Vue 大屏，支持 `DEMO_MODE` 零基础设施运行。
- 黑盒测试统一为 `ai-assistant-tests`（api/ + ui/），提供共用 `.env` 模板与跨平台 E2E 脚本。
- 根目录 npm workspace：统一安装、测试与构建前端和数据平台 Web。

### Changed

- Spring AI 升级至 1.1.8（Spring Boot 3.5 线最新稳定版），适配 MCP SSE 配置类与 Embedding Options API。
- 仓库结构重组：`data-platform/` → `ai-assistant-data-platform/`，`ai-assistant-e2e/`、`ai-assistant-ui-e2e/` → `ai-assistant-tests/`；CI 与文档路径同步。
- 共享配置收敛到 `application-common.yml`（Nacos / Feign / Kafka / Redis / JWT），各服务 yml 仅保留端口与业务参数。
- 业务异常返回真实 HTTP 状态码，前端仍读取响应体 `msg` 展示提示。

### Security

- 挂号支付/取消/取号改为锁内条件更新并重读支付状态，修复并发付款漏退款、越权访问与租户校验缺口；患者查询需手机号 + 身份证并脱敏展示。
- Refresh Token 改为家族轮换，支持重放撤销与 30 天绝对过期；短信验证码原子校验；登录锁定按 `X-Forwarded-For` 末段识别来源。
- 网关预约白名单收窄并拦截路径穿越；MCP 限流改为 fail-closed，未认证请求独立限速。
- AI 急症红线支持否定词豁免；`pending_action` 状态机防重放并自动过期清理；知识库删除先清向量再删记录。
- 数据平台生产禁用内存存储与不安全质量 SQL，调度任务增删改实时同步，同步锁支持僵尸接管。

### Fixed

- 修复患者预约支付/取消因脱敏身份证回传导致的失败，以及列表分页竞态、Markdown 协议白名单等前端问题。

### Docs

- 公开文档收敛为架构说明（含阅读路线）与 MCP 接入手册，测试说明内聚到各套件目录。
- README 首屏重写：核心能力、快速体验、Mermaid 架构与文档入口。
- 架构说明补充数据平台链路与 Refresh Token 调试键；数据平台 README 同步 npm workspace 与新测试目录，过程性计划/验收记录移出仓库。

## [1.0.0] - 2026-10-19

### Added

- Spring Cloud 微服务化：gateway、auth、system、hospital、registration、ai-chat 六进程，Nacos 注册与配置。
- 员工/患者双身份 JWT：权限快照、刷新令牌轮换、登出黑名单、踢下线、手机号验证码与 PC 扫码确认登录。
- AI 能力：SSE 流式聊天、多会话、AI 导诊、智能挂号二次确认、本地 ONNX 嵌入、RAG 引用与急症红线。
- RAG 增强：结构切片、chunk 级引用、groundedness、Simple/Elasticsearch 双存储、BM25 + kNN 混合检索。
- AI 工程化：模型故障转移、请求超时、token 计量、会话摘要与预算、工具护栏、评测套件（安全/分诊/RAG）。
- 医院业务：多医院/科室/员工/出勤、排班号源、预约取号/退号/就诊、数据权限。
- 工程底座：Kafka 异步（站内信/审计/知识库）、Zipkin 链路追踪、Prometheus 指标、操作审计、网关限流、登录失败锁定。
- 前端：Vue 3 + Element Plus，员工端与患者端 H5，统一 SSE 封装与双令牌续期。

### Security

- 网关身份与路径配对、`/inner/**` 外部阻断、内部调用令牌。
- AI 写工具患者医院归属强校验；角色管理按医院隔离并接入 `ai:role:manage` 权限。
- 生产 profile 密钥守卫、CORS 白名单、弱口令与 console 短信通道拦截。

### Fixed

- 修复流式链路取消/截断时尾部消息丢失、风控守卫覆盖不全、摘要被挤出消息丢失等问题。
- 修复患者历史加载与发送消息的竞态，以及刷新令牌过期后的登出行为。

[Unreleased]: https://github.com/yh230/ai-assistant/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/yh230/ai-assistant/releases/tag/v1.0.0
