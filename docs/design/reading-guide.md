# 阅读路线

这个仓库模块多、横切能力多，直接从头读容易迷路。下面给一条按“请求生命周期”组织的路线：先看主链路，再按兴趣深入 AI、并发与异步。

## 1. 30 分钟：先建立全局感

1. 读根目录 [README](../../README.md) 的「核心能力」与「架构一览」。
2. 读 [架构说明](architecture.md) 的服务边界与身份链路。
3. 打开 `ai-assistant-backend/pom.xml`，确认 9 个模块的依赖关系。
4. 浏览 `sql/init.sql`，知道四库分别存什么。
5. 启动前端和后端，按「快速开始」走一遍患者注册 → 导诊 → 挂号确认。

## 2. 半天：跟一次请求

建议按下面顺序读代码，每一步都带着一个问题：

| 步骤 | 入口 | 要回答的问题 |
|------|------|--------------|
| 1 | `gateway/.../filter/JwtAuthGlobalFilter.java` | 哪些路径免登录？身份和路径怎么配对？`/inner/**` 为什么外部不可达？ |
| 2 | `common-security/.../SecurityAutoConfiguration.java` | 服务侧如何从 JWT 还原 `UserSnapshot`？权限不足返回什么？ |
| 3 | `services/auth-service/.../AuthServiceImpl.java` | 登录、刷新、登出分别做了什么？refresh token 如何轮换？ |
| 4 | `services/system-service/.../UserServiceImpl.java`、`RoleServiceImpl.java` | RBAC 的权限快照何时失效？ |
| 5 | `services/registration-service/.../RegistrationServiceImpl.java` | 预约状态机、锁顺序、幂等键、号源抢占分别在哪？ |
| 6 | `services/hospital-service/...` 与 `common/.../DataScope*` | 数据权限如何按医院/科室/医生过滤？ |

读完这六步，普通业务请求的主链路就通了。

## 3. 一天：深入 AI 与异步

### AI 对话

- `services/ai-chat-service/.../PatientChatServiceImpl.java`：患者 SSE 入口、会话归属、急症短路、待确认操作。
- `services/ai-chat-service/.../ChatServiceImpl.java`：员工侧聊天、非流式兼容接口、模型选择。
- `services/ai-chat-service/.../ConversationMemory.java`：摘要、滑动窗口、token 预算。
- `services/ai-chat-service/.../ModelStreamExecutor.java`：流式调用、首 token 前故障转移。
- `services/ai-chat-service/.../tool/GuardedToolCallback.java`：工具白名单、次数上限、超时与错误结构化。
- `services/ai-chat-service/.../PendingActionServiceImpl.java`、`ActionExecutor.java`：二次确认状态机与写操作执行。
- `services/ai-chat-service/.../rag/`：切片、嵌入、Simple/ES 存储、RRF 融合、引用与 groundedness。

### 异步与可观测

- `api/.../mq/MqTopics.java`：主题与死信命名。
- `ai-chat-service/.../mq/KnowledgeTaskConsumer.java`：知识库异步向量化与幂等。
- `common-security/.../operlog/`：审计切面与 Kafka 上报。
- `scripts/nacos-common-shared.yaml`：共享配置与 AI 参数。

## 4. 重点技术点索引

| 想学什么 | 先看哪里 |
|----------|----------|
| JWT 双身份与网关鉴权 | `JwtAuthGlobalFilter`、`JwtTokenService`、`UserSnapshotStore` |
| Spring Security 方法级权限 | Controller 上的 `@PreAuthorize`、`SecurityAutoConfiguration` |
| 多租户与数据权限 | `DataScopeResolver`、各 Service 的 `assertReadable` / `hospitalId` 过滤 |
| Redis 分布式锁 | `RegistrationServiceImpl#withLock`、`ScheduleLock` |
| 幂等与状态机 | `RegistrationStatus`、`buildIdempotencyKey`、`pending_action` |
| SSE 流式输出 | `PatientChatServiceImpl`、`ChatMessagePersister` |
| Spring AI Tool Calling | `PatientTools`、`GuardedToolCallback`、`ActionExecutor` |
| RAG | `KnowledgeChunker`、`ElasticsearchKnowledgeStore`、`GroundednessChecker` |
| Kafka 可靠性 | `MqTopics`、消费者手动 ack、死信处理 |
| 评测 | [AI 评测](evaluation.md)、`ai-assistant-e2e/eval/` |

## 5. 代码约定

- 统一返回 `Result<T>` / `PageResult<T>`，业务失败抛 `BusinessException`。
- 控制器只做参数与权限，业务规则放 Service；跨服务调用通过 `api` 模块的 Feign 客户端。
- 数据权限优先在查询层过滤，AI 写工具必须做服务端归属校验。
- AI 输出必须带免责文案；输入/输出风控不可绕过。
- 新增配置优先放 Nacos 共享配置，代码里保留可本地启动的默认值。

## 6. 调试建议

- 先看网关日志中的 `traceId`，再用同一个 `traceId` 搜各服务日志。
- Redis 常用键：`auth:user:*`（员工快照）、`auth:patient:*`（患者快照）、`jwt:blacklist:*`、`jwt:invalid-after:*`。
- 预约问题先看 `registration` 的状态与 `pay_status`，再看号源 `number_source` 与排班锁。
- AI 问题先确认模型是否可用，再检查工具调用日志与 `pending_action` 状态。
- 知识库问题先看文档状态（PENDING/PROCESSING/COMPLETED/FAILED），再看消费者日志与向量存储实现。

## 7. 三个容易混淆的概念

1. **AI 人设角色 vs RBAC 角色**：`ai_role` 是对话人设；`sys_role` 是员工权限角色，两者无关。
2. **SimpleVectorStore vs Elasticsearch**：默认 `simple` 是单机文件；`elasticsearch` 才启用 BM25 + kNN 混合检索。
3. **员工聊天 vs 患者聊天**：入口、风控、工具和会话隔离都不同，不要只读其中一个就下结论。

## 8. 建议的动手练习

- 给预约状态机补一个非法流转测试。
- 把 `simple` 向量存储切到 ES，对比同一问题的检索结果。
- 给一个只读工具增加参数校验，观察 `GuardedToolCallback` 的错误结构。
- 为一条评测用例补 golden 数据，运行 `pytest -m "eval and not ai"`。
