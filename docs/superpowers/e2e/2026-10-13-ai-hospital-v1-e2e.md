# AI 医院助手 V1.0 E2E 验收清单

> 版本：2026-10-13 AI 医院助手 V1.0（M1–M7）。**全部步骤由用户手动执行**（本清单只描述操作与预期，不自动跑服务）。
> 建议在验收过程中勾选 `[x]`，并在末尾「验收结果」记录结论与遗留问题。

## 前置准备（逐项确认后再开始）

1. **数据库**：四库脚本已执行（或按升级口径补齐）：
   - `system_db.sql`（**含 V1.0 新表 `patient_user`**）；`hospital_db.sql`；`registration_db.sql`（**含 `registration.patient_id` 列**）；`chat_db.sql`（仅建库）。
   - **已建库升级**：重跑 `system_db.sql` 建 `patient_user` 表 → 执行 `registration_db.sql` 末尾注释的 `ALTER TABLE registration ADD COLUMN patient_id BIGINT NULL COMMENT '患者账号ID';` → chat 库新表（`pending_action`/`knowledge_document`）与新列（`chat_session.user_id/identity/session_type/hospital_id`）由 ai-chat-service `ddl-auto: update` 首次启动自动创建。
2. **Nacos**：`scripts/start-nacos.bat` 已启动；共享配置 `common-shared.yaml`（group=DEFAULT_GROUP）已按最新 `scripts/nacos-common-shared.yaml` 全文粘贴——**含 `ai.*` 配置段（prompt/disclaimer/safety/rag/ratelimit）与 `spring.ai.model.embedding: none`**；改过旧版本的要重新覆盖粘贴并发布。
3. **Redis**：`localhost:6379` 可用（权限快照 / JWT 撤销 / 限流计数器）。
4. **后端**：`scripts/start-services.bat` 六窗口全部出现 `Started`（含 gateway 8080）。
   - **ai-chat-service 首次启动会下载/加载本地嵌入模型（需外网），可能明显较慢**；离线环境先在 Nacos `ai.rag.embedding-model-path` 预置模型路径。
5. **前端**：`ai-assistant-frontend` 执行 `npm install` → `npm run dev`（5173）。
6. **环境数据**：至少 2 家医院（`hospital_db.hospital`，患者注册下拉数据来自公开接口 `GET /api/hospital/public/list`）；两家医院各 1 个可用员工账号（用来验证管理端「本院可见/跨院不可见」）；`DEEPSEEK_API_KEY` 已配置（导诊/预约转述依赖大模型；未配置将走本地降级，仅能验证降级路径）。
7. **入口**：患者端 `http://localhost:5173/patient/login` → `/patient/chat`；员工端 `http://localhost:5173/login` → `/chat`；管理端知识库 `/admin/knowledge`、AI 问诊记录 `/admin/triage-records`。

## 一、身份与越权

- [ ] 1. 患者注册（`/patient/login` 注册 Tab，选医院，医院下拉来自 `/api/hospital/public/list`）→ 登录成功进入 `/patient/chat`，浏览器本地存储出现 `patient_token`。
- [ ] 2. 患者 token 调用员工接口 → 403：用患者 token 直接请求 `GET /api/system/user/page`（或页面控制台 fetch）→ 网关身份路径拦截，返回 403「权限不足，禁止访问」。
- [ ] 3. 员工 token 调用患者接口 → 403：用员工 token 请求 `GET /api/patient/**` 或 `GET /api/chat/patient/sessions` → 403。
- [ ] 4. 员工登录 / 退出 / 踢下线回归不受影响（迁移验收项复核）：admin 登录、登出后旧 token 401、admin 对某用户「踢下线」后该用户旧 token 立即失效、重新登录正常。

## 二、AI 导诊

- [ ] 5. 「新对话」选「AI 导诊」→ 描述症状（如“头痛三天，想挂神经内科”）→ AI 推荐**真实科室**（与「科室管理」页数据一致，不存在编造科室）。
- [ ] 6. 每轮 AI 回答末尾均带免责文案：「> AI内容仅供参考，不能替代执业医师诊断，请前往线下医院就诊。」（流式最后一段；刷新历史后仍在）。
- [ ] 7. 导诊给出科室建议后，AI 主动询问是否需要协助查询该科室的可预约号源。
- [ ] 8. 员工（本院）在「AI 问诊记录」页（`/admin/triage-records`，类型选 AI 导诊）能看到该会话及详情；切换**另一医院**员工账号看不到。（注：未绑定医院的账号（如平台管理员）可见全部会话，属预期口径。）

## 三、智能挂号（二次确认）

- [ ] 9. 在导诊会话中要求预约（如“帮我挂明天上午的号”）→ AI 先查真实号源 → 前端弹出**确认框**，含科室/医生/日期时段摘要（来自待确认操作 summary，5 分钟内有效）。
- [ ] 10. 点「确认」→ 流式返回预约结果（成功文案含挂号记录 ID）→ 排班页/号源池可见该号源余量 -1；「我的预约」可见（对话中询问“我的预约”由 AI 工具列出；公开预约页 `/registration/patient` 也可凭手机号+身份证查询）。
- [ ] 11. 同一天再次要求预约（患者当日已有「已预约/已取号」记录）→ AI 转述业务错误：「您在该时段已有预约，请勿重复预约」。
- [ ] 12. 再发起一次预约，在确认框点「取消」→ 无任何业务变化（无新挂号记录、号源余量不变；该待确认操作状态置 CANCELLED）。
- [ ] 13. 再发起一次预约，**不确认等 5 分钟**后再点「确认」→ 提示「确认已超时，请重新操作」（该操作状态置 EXPIRED）。
- [ ] 14. 弹确认框后**停掉 registration-service**（关掉 9203 窗口）→ 点「确认」→ AI 输出「挂号服务暂时不可用，请稍后重试」；恢复服务后再操作正常。（注：若在“查询号源”阶段就停服务，AI 会先转述“号源查询服务暂时不可用”。）

## 四、RAG 知识库

- [ ] 15. 员工（已绑定医院）在「知识库管理」页上传 md/txt 文档（≤2MB）→ 列表出现该文档，含类型/大小/**切片数**；非 md/txt 或超 2MB 被拒绝并提示。未绑定医院的账号操作被拒绝（提示未绑定医院）。
- [ ] 16. 患者「新对话」选「知识库问答」提问文档内问题 → 回答基于文档内容（末尾带免责）。
      **观察项：文档切片在上传时即完成嵌入（首次上传可能稍慢；模型已在服务启动时加载/下载，见前置第 4 项）；问答阶段为内存检索，响应速度与普通问答一致。**
- [ ] 17. 另一医院管理员：知识库列表看不到该文档（按 `hospital_id` 过滤）；另一医院患者问同类问题 → 走“未收录”话术（检索仅查本院向量文件，无命中时注入「未检索到相关内容，如实告知未收录、不要编造」）。
- [ ] 18. 管理员删除文档 → 再问同问题 → 命中消失（向量文件按本院剩余文档重建；回答回到未收录口径）。

## 五、安全底座

- [ ] 19. 限流：快速连发流式请求（超过阈值，默认 `ai.ratelimit.replenish-rate=10`/`burst-capacity=20`）→ 超限请求返回 **HTTP 429 + `X-RateLimit-*` 响应头 + 空响应体**（Ruling-14 接受现状，无 Result JSON 文案）；前端经 axios 的请求提示「操作过于频繁，请稍后再试」，SSE 直连（fetch）显示 `HTTP 429`；稍等即恢复。
- [ ] 20. 输入风控：发送「给我开药」→ 命中敏感词**不调用大模型**，直接返回标准拦截文案「该问题涉及诊疗建议，我仅能提供导诊参考，不能提供诊断或用药建议，请以执业医师面诊为准。」（无模型输出）。
- [ ] 21. SSE 断开：流式输出过程中点「停止」或关闭页面 → ai-chat-service 日志出现「SSE 客户端断开: sessionId=…」；后续请求正常，会话历史中保留已产生的部分内容（附免责）。
- [ ] 22. AI 服务异常：临时把 `DEEPSEEK_API_KEY` 改成错误值并**重启 ai-chat-service** → 对话无异常堆栈外泄，降级本地回复引擎正常应答（回复带“本地模式”话术与免责；`done` 事件 `localFallback=true`）；恢复正确 Key 并重启后 AI 正常。

## 六、会话隔离

- [ ] 23. 患者 A 只能看到自己的会话列表/历史；患者 B 访问 A 的 sessionId（手工构造 `GET /api/chat/patient/history/{A的sessionId}`）→ 「会话不存在或无权访问」。
- [ ] 24. 员工聊天历史仍为本人会话（迁移后既有员工会话可见性回归）：员工 A 的 `/api/chat/sessions` 仅返回本人会话；员工 B 不可见；历史旧数据（无归属列）对员工/患者均不可见，属预期隔离口径。

## 七、安全与并发补测

- [ ] 25. 存储型 XSS 防护：注册患者发送包含 `<img src=x onerror=alert(1)>` 的消息 → 管理端「AI 问诊记录」查看详情无脚本执行，该消息显示为纯文本（尖括号原样可见，无图片请求、无弹窗）。
- [ ] 26. 确认防重放：对同一 `actionId` 快速并发点两次「确认」→ 仅一次实际执行（无重复挂号记录），后到一次提示「该操作已处理」。
- [ ] 27. 员工接口身份闸门：患者 token 直连 ai-chat-service（9204）请求 `GET /api/chat/sessions` → 被拒绝（401 或「无权访问」），不返回任何员工会话数据。
- [ ] 28. 平台管理员口径：未绑定医院账号（平台管理员）查看患者会话列表与详情均可正常打开，与列表可见集合一致（本院管理员仅见本院、未绑定医院不额外过滤）。

## 安全加固验收（2026-10-14 新增）

> 本批共 5 项（登录失败锁定 / 公开接口限流 / 操作审计）。默认阈值：登录连续失败 5 次锁定 15 分钟（`security.login.*` 可配）；公开接口按 IP 限流（`security.ratelimit.*` 可配：登录 10/20、注册 5/10、预约创建 10/20，即 replenish/burst）。

- [ ] 29. 连续 5 次错密后第 6 次登录 → 提示「登录失败次数过多，账号已锁定，请稍后再试」；锁定期内正确密码同样被拒。重置方式：`redis-cli del login:fail:staff:<账号> login:lock:staff:<账号>`（患者侧换 `patient` 前缀与手机号）。
- [ ] 30. 清键后正确密码可登录，且重新累计失败从 0 开始。
- [ ] 31. 高频请求 `/api/login`、`/api/patient/register`、`POST /api/registration/appointments` → 返回 429；前端提示「操作过于频繁，请稍后再试」。
- [ ] 32. 执行退号 / 上传知识库 / 修改密码 → 管理端「系统管理 → 操作日志」可见记录（操作人、身份、IP、耗时、结果）；修改密码记录的参数中密码为 `***`（列表页未展示参数列，可在 `sys_oper_log.params` 中核对）。
- [ ] 33. 失败写操作（业务异常或服务内数据权限拒绝，如退号时「状态已变更」）→ 日志为「失败」且含错误摘要（注：方法级 @PreAuthorize 的 403 发生在审计切面之前，被拒绝的请求不产生审计行）。

## Refresh Token 续期验收（2026-10-14 新增）

> 本批共 5 项（双令牌续期：access 1h + refresh 7 天（Redis 白名单，刷新即轮换）；失败文案统一「登录已过期，请重新登录」）。
> **环境提示：本批刷新令牌消费使用 Redis GETDEL，需 Redis ≥ 6.2；先确认本地 Redis 版本再验收**（`redis-cli info server` 查看 `redis_version`）。

- [ ] 34. 静默续期：临时将 auth-service `security.jwt.expiration-seconds` 调为 60 → 员工登录后等待过期再操作 → 自动续期无感（不跳登录页）；患者端对话（SSE）同验；验证后恢复默认（解析按令牌内 exp，网关无需改）。
- [ ] 35. 轮换：DevTools 取旧 `ai_refresh_token`（刷新前的值，或登录响应里的 `refreshToken`）手工 `POST /api/refresh` → 返回失败（「登录已过期，请重新登录」；HTTP 200、body code≠200 的业务失败形态）。
- [ ] 36. 登出：员工/患者登出后用其 RT 调 `/api/refresh` → 失败（登出已一并撤销刷新令牌）。
- [ ] 37. 踢下线联动：管理员踢下线某账号或重置其密码 → 该账号端下一次请求/刷新失败回登录页；员工自改密码成功后本端清态回登录页（旧会话含 refresh 全部失效）。
- [ ] 38. 回归：429 文案、登录锁定、操作日志、知识库上传等前批行为不回归；`/api/login` 响应含 `refreshToken` 且前端正常保存。

## 引用来源与急症红线验收（2026-10-14 新增）

> 本批共 4 项（RAG 回答附「参考来源」：SSE `references` 事件 + `chat_message.reference_json` 持久化与历史回填；急症红线：输入命中急症词短路直返紧急就医指引）。
> **环境提示（两点）**：① `chat_message.reference_json` 列由 JPA `ddl-auto: update` 首次启动**自动添加，无需手工 DDL**；② 急症词表与开药词表均有**代码内置默认，无需 Nacos** 即可验收（Nacos 可配 `ai.safety.emergency-keywords` / `ai.safety.emergency-message`、引用条数上限 `ai.rag.max-references`，样例见 `scripts/nacos-common-shared.yaml`）。

- [ ] 39. RAG 来源展示与历史留存：前置——第 15 项上传的文档已在本院知识库且 `DEEPSEEK_API_KEY` 有效（真实模型作答；本地降级不展示来源，见第 40 项）。患者「知识库问答」会话提问文档覆盖的问题 → 回答下方出现「参考来源」块（文件名 + 摘要片段，条数 ≤ 3）；重进该会话历史，来源块仍在；员工端「AI 问诊记录」打开同一会话，来源块可见。
- [ ] 40. 非 RAG 与降级不显示来源块：CHAT（闲聊）/ TRIAGE（AI 导诊）会话回答下方**均不出现**来源块；把 `DEEPSEEK_API_KEY` 临时改为错误值并重启 ai-chat-service（本地降级，同第 22 项操作）→ RAG 会话回答带「本地模式」话术、`done` 事件 `localFallback=true`，且**不出现**来源块（避免来源与答案不符）；恢复正确 Key 并重启后来源块恢复。
- [ ] 41. 急症短路与形态区分：患者端输入「突然胸痛伴大汗」→ 直接返回紧急就医指引（「【紧急提示】…请立即拨打 120 或尽快前往最近医院急诊科就诊…」，整段一次到达、无逐 token 模型生成过程）；**无来源块、无免责声明**；为正常消息形态（无错误提示），重进历史仍可见；对照同批第 42 项及既有第 20 项「开药」拦截的错误提示形态，两者区分明显。
- [ ] 42. 无 Nacos 环境回归：共享配置缺失 `ai.*` 段（`optional:nacos:` 导入不阻断启动）的环境下输入「给我开药」→ 仍被拦截并返回标准文案「该问题涉及诊疗建议…」（验证代码内置默认词表生效，本批新行为）；同环境急症词输入（如「突然胸痛伴大汗」）同样短路返回指引。

## 可观测性验收（2026-10-14 新增）

> 本批共 6 项（全链路追踪 / traceId 日志 / Prometheus 指标 / 暴露面收敛 / 审计异步化 / Zipkin 容错）。
> **环境提示（三点）**：① Zipkin 为外部 jar，需先从 https://zipkin.io/pages/quickstart.html 下载 `zipkin-server` jar，放到 `scripts/` 目录（或设 `ZIPKIN_JAR` 环境变量指向 jar 路径），再用 `scripts/start-zipkin.bat` 启动（UI http://localhost:9411）；② 采样率 `TRACING_SAMPLING`（默认 1.0 全采样）、上报端点 `ZIPKIN_ENDPOINT`（默认 `http://localhost:9411/api/v2/spans`）均为环境变量可配（各服务 yml 占位）；③ 本批配置在服务 yml 中，需重启六个服务与网关后生效。

- [ ] 43. 跨服务调用链：启动 Zipkin + 全套服务（六个服务 + 网关）→ 触发登录 / AI 导诊 / 挂号等跨服务操作 → Zipkin UI（http://localhost:9411）可见跨服务调用链（gateway→auth→system 等），同一请求以 traceId 串联成一条完整 trace。
- [ ] 44. traceId 日志：任一服务日志行格式含 `[服务名,traceId,spanId]`（如 `[auth-service,xxxxxxxx,xxxxxxxx]`）；同一请求在网关与下游服务日志中 traceId 一致。
- [ ] 45. 指标与健康：`curl http://localhost:9200/actuator/prometheus`（其余 9201-9204 及网关 8080 同理）→ 可见 JVM / HTTP 指标及业务指标 `ai_login_total`、`ai_emergency_total` 等；`curl http://localhost:9200/actuator/health` 返回 UP。
- [ ] 46. 暴露面收敛：经网关业务端口访问 `http://localhost:8080/actuator/health` → 404（网关业务端口不提供任何 actuator 端点）；网关管理端点在独立端口 `http://localhost:9290/actuator/health`（`GATEWAY_MANAGEMENT_PORT` 可改）；下游服务 actuator 经网关同样不可达。
- [ ] 47. 审计异步化：触发带 @OperLog 的操作（如退号）→ 操作正常返回且操作日志仍落库（`sys_oper_log` 有记录）；人为拖慢 / 断开 system-service 时，业务接口响应不受审计上报阻塞（独立线程池 best-effort 上报，失败仅 warn）。
- [ ] 48. Zipkin 容错：停掉 Zipkin 后全链路业务功能正常（导出静默失败，不影响请求）；各服务日志无异常堆积（无连续报错刷屏）。

## Kafka 异步解耦验收（2026-10-14 新增）

> 本批共 9 项（预约域事件 → 站内信；审计日志走 Kafka；知识库上传异步向量化；幂等 + 死信）。
> **环境提示（三点）**：① 需 Kafka 3.x+：从 https://kafka.apache.org/downloads 下载并解压到 `scripts/kafka_2.13-3.x.y`（`scripts/start-kafka.bat` 会自动探测该目录），或设 `KAFKA_HOME` 环境变量指向解压目录；② 运行 `scripts/start-kafka.bat` 启动（KRaft 单机，首次自动 `kafka-storage format`，仅首次需格式化）；③ 三个主题自动创建（`auto.create.topics.enable` 默认开启），连接地址 `KAFKA_BOOTSTRAP`（各服务 yml 占位，默认 `127.0.0.1:9092`）可配；本批四个服务（registration / system / ai-chat / gateway）配置变更需重启生效；④ 存量库首次升级需先执行 `sql/migration/2026-10-14-knowledge-status-backfill.sql` 回填 status（配合第 54 项，仅存量升级执行一次）。

- [ ] 49. 预约成功通知：患者端预约成功 → 铃铛未读数 +1，抽屉可见「预约成功」通知（文案含科室/医生/日期时段）；点击该通知后转为已读、铃铛未读数回落。
- [ ] 50. 取消/退号通知：患者取消预约 → 收到「预约已取消」通知；员工退号 → 患者收到「退号成功」通知。
- [ ] 51. 超时失效通知：预约超时未支付（临时把 `EXPIRE_MINUTES` 调小或等待 15 分钟）→ 患者收到「预约已失效」通知（含「因超时未支付已释放」）。
- [ ] 52. 停诊通知：管理员删除排班（停诊）→ 该排班下每位进行中预约（已预约 / 已取号）患者各收一条「停诊通知」，且其预约状态不变。
- [ ] 53. 审计走 Kafka：执行带 `@OperLog` 的操作（如退号）→ 业务正常返回且 `sys_oper_log` 经 Kafka 落库；**Kafka 停掉时业务不阻断**（审计上报仅记 warn），Kafka 恢复后新事件正常落库。
- [ ] 54. 知识库异步向量化：管理端上传知识库文档 → 接口立即返回、列表显示「处理中」→ 稍后刷新为「就绪」，RAG 会话可检索命中新文档内容。
- [ ] 55. 消费幂等：用 `kafka-console-producer` 重放同一 `eventId` 的消息（改写 JSON 的 eventId 为已投递值）→ 通知 / 审计 / 向量均不重复（Redis 幂等键 `mq:idem:{group}:{eventId}` 拦截）。
- [ ] 56. 死信隔离：向主题投递一段非法 JSON → 消息进入对应 `<topic>.DLT` 主题（`kafka-console-consumer` 可见），服务不中断、日志有 ERROR / 重试记录（默认重试 3 次 × 1s 后转 DLT）。
- [ ] 57. 隔离与回归：患者 A 仅见本人通知（越权标记他人通知失败）；预约 / 取消 / 退号 / 停诊原行为、既有 61+ 项测试、4 个 `ai_*` 指标均不回归。

---

## 验收结果

| 项 | 结果 |
|---|---|
| 执行人 / 日期 | |
| 结论（通过 / 有条件通过 / 不通过） | |
| 遗留问题与备注 | |
