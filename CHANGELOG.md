# Changelog

本文件记录项目的版本级变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [Unreleased]

### Added

- 开源门面与可复现基础：MIT License、Maven Wrapper、跨平台启动脚本、GitHub Actions CI（后端单测 + 前端构建/测试）。

### Docs

- 新增公开设计文档：架构说明、阅读路线、AI 评测。
- README 首屏重写：核心能力、快速体验、Mermaid 架构与文档入口。

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
