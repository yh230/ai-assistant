# 医院数据治理与运营大屏平台（data-platform）

> 状态：**代码级交付 + DEMO_MODE 本机验收**。`DEMO_MODE=true` 已用 SQLite 种子数据跑通后端 API、Vue 大屏与治理页面；Kafka、ClickHouse、Flink 仍未下载、未部署、未启动，真实链路、集成测试与 E2E 未执行。
> 本仓库任何内容都不代表端到端已通过，详见 [验证报告](docs/verification-2026-11-04.md)。

## 1. 项目定位

`data-platform` 是 ai-assistant 的**下游数据平台**，不修改 ai-assistant 现有服务，只读接入其业务库：

- 对设计文档 §7.3 的 7 张源表（`hospital_db`、`registration_db`、`chat_db`）做全量 + 增量抽取，按水位断点续跑；`system_db` 与 `hospital_db.attendance` 按设计延后，V1 不支持。
- 消费 ai-assistant 现有 `ai.registration.event` 实时事件，与历史回补数据统一入仓。
- 在 ClickHouse 内完成 ODS / DWD / ADS 分层建模与去重。
- 提供元数据浏览、6 类标准质量规则（另支持自定义 SQL）、质量评分、告警记录与 Webhook 投递接口；V1 未接入指标采集器与自动评估调度。
- 提供 1920×1080 运营大屏与治理后台，前端可用 `VITE_USE_MOCK=true` 无后端预览。
- 交付 PyFlink 实时聚合作业代码与 Flink SQL：**代码已实现，未部署、未运行验证**。

设计文档：[docs/2026-11-04-design.md](docs/2026-11-04-design.md)；实施计划：[docs/plans/2026-11-04-data-platform.md](docs/plans/2026-11-04-data-platform.md)。

## 2. 架构

```text
ai-assistant MySQL（只读）
  hospital_db / registration_db / chat_db（7 张注册源表）
        │  全量 keyset 分页 + update_time 增量（水位 / 断点）
        ▼
   Extractor（发布前脱敏）───────────────► Kafka `dp.ods.<db>.<table>`
                                                   │
   ai.registration.event ──┬──────────────────────┤
                           │                      ▼
                           │              Consumer（批量 / 清洗 / 幂等 / 隔离 / DLT）
                           │                      │
                           │                      ▼
                           │              ClickHouse ODS ──► DWD ──► ADS
                           │                      │
                           │                      ├──► 质量规则手工执行 ──► 告警记录 + Webhook
                           │                      └──► FastAPI `/api/screen`（SSE 5s）
                           │                                   │
                           └──► Flink（代码展示，未部署）        ▼
                                 └──► ads_realtime_registration_5m   Vue 3 + ECharts 大屏 / 治理后台
```

端口与适配器：`EventPublisher` / `EventConsumer`、`OlapStore`、`SourceRepository` 均以接口隔离，业务层只依赖端口；生产实现为 Kafka / ClickHouse / MySQL，单元测试使用内存 Fake，因此缺少外部依赖时仍可完成静态检查与纯逻辑单测。

源表契约集中在 `etl/source_registry.py`：只有注册表中的 `(schema, table) -> (target_table, cleaner)` 组合可以创建同步任务、生成消费 Topic 或写入 ODS。

## 3. 目录说明

```text
data-platform/
├── app/                      # FastAPI 应用
│   ├── main.py               # 应用入口、异常处理、路由注册
│   ├── core/                 # 配置、数据库、加解密、响应、异常、平台时钟
│   ├── api/                  # 数据源、元数据、同步、质量、告警、大屏路由
│   ├── models/               # SQLAlchemy 元数据模型
│   ├── schemas/              # Pydantic 请求/响应模型
│   ├── services/             # 治理、质量、告警、大屏业务逻辑
│   └── scheduler.py          # APScheduler 入口（需 DP_ENABLE_SCHEDULER=true）
├── etl/                      # 数据工程核心
│   ├── ports/                # EventBus / OlapStore / SourceRepository / RunStore 接口
│   ├── adapters/             # Kafka、ClickHouse、MySQL 与内存 Fake 实现
│   ├── source_registry.py    # 7 张受支持源表与 ODS 目标表契约
│   ├── privacy.py            # 发布前 PII 脱敏
│   ├── extractor.py          # 全量 + 增量抽取
│   ├── consumer.py           # 批量消费、清洗、隔离、脱敏 DLT
│   ├── consumer_main.py      # 常驻消费进程入口
│   ├── cleaning.py           # 清洗与脱敏（接受预计算掩码）
│   ├── transform.py          # DDL 拆分与 DWD / ADS SQL 执行
│   ├── transform_main.py     # 手工转换 CLI：--stage ddl|dwd|ads --ds YYYY-MM-DD
│   ├── quality.py            # 质量规则 SQL 生成与评分
│   ├── alerting.py           # 告警判定、去重、冷却、Webhook
│   ├── realtime.py           # ai.registration.event 处理
│   ├── envelope.py           # 事件信封
│   ├── watermark.py          # 水位计算
│   └── sql/                  # ClickHouse DDL 与 DWD / ADS SQL
├── flink/                    # 实时聚合代码展示（未部署）
│   ├── jobs/realtime_registration_agg.py
│   ├── sql/realtime_registration_agg.sql
│   ├── Dockerfile
│   └── README.md
├── web/                      # Vue 3 + Vite + ECharts + Element Plus
│   ├── src/views/            # 大屏与治理后台页面
│   ├── src/api/              # axios 客户端与 SSE 地址
│   ├── src/mock/             # 无后端预览夹具（非数据正确性证据）
│   ├── src/utils/
│   └── tests/                # node --test 图表配置测试
├── tests/
│   ├── unit/                 # 标准库 unittest，依赖缺失时 skip
│   └── integration/          # 仅说明文档，本轮未执行
├── docs/
│   ├── 2026-11-04-design.md
│   ├── plans/2026-11-04-data-platform.md
│   └── verification-2026-11-04.md
├── pyproject.toml
├── .env.example
└── README.md
```

## 4. 状态矩阵

三档口径：**已实现** = 代码存在；**已静态验证** = 通过编译或纯逻辑单测（不代表运行时行为）；**未验证** = 本轮未执行。

| 模块 | 已实现 | 已静态验证 | 未验证 |
| --- | --- | --- | --- |
| 数据源 CRUD、连接测试（回写状态）、元数据采集、密码 Fernet 加密 | 是 | Python 编译；服务层测试依赖缺失时 SKIP | MySQL 连接与采集 |
| 同步任务、运行记录、全量 / 增量抽取、水位断点 | 是 | 水位 / Extractor / 服务层单测（依赖缺失时 SKIP） | 真实 MySQL 抽取 |
| 源表契约、发布前 PII 脱敏 | 是 | 注册表 / 脱敏 / Extractor 单测执行 | 真实数据流 |
| Kafka 事件信封、Producer / Consumer 适配器 | 是 | 信封、消费管线（内存 Fake）单测执行；适配器仅编译 | 真实 Kafka 收发、位点提交 |
| 清洗、隔离、脱敏 DLT、实时预约事件 | 是 | 清洗 / 消费 / 实时事件单测执行 | 真实数据与 ClickHouse 写入 |
| ClickHouse ODS / DWD / ADS / OPS DDL 与转换 SQL | 是 | SQL 文件结构与时区单测执行；DDL 拆分单测执行 | DDL 执行、`ReplacingMergeTree` 去重 |
| 质量规则（6 类 + 自定义 SQL）、评分 | 是 | 规则生成 / 评分单测执行；服务层 / API 测试 SKIP | 在 ClickHouse 执行 |
| 告警规则、去重、冷却、Webhook | 是 | 判定 / 指纹 / 冷却 / Webhook 发送逻辑单测执行 | 指标采集、自动评估、Webhook 实际投递 |
| 大屏 API 与 SSE | 是 | 大屏服务层与仓库单测执行；路由 / SSE 测试 SKIP | 真实查询与 SSE 推送 |
| Vue 3 大屏与治理后台 | 是 | `node --test` 工具函数测试执行；`npm run build` 通过 | 真实浏览器交互回归 |
| DEMO_MODE 零基础设施演示 | 是 | SQLite 种子 + 演示大屏仓库单测执行；本机后端 API、大屏与治理页面人工验收 | 仅演示数据，不代表真实链路 |
| Flink 实时聚合 | 是（代码展示） | `py_compile` 退出码 0 | 镜像构建、作业提交、结果查询 |
| APScheduler 调度 | 是 | 仅 Python 编译；构建与 cron 测试 SKIP | 真实定时触发 |

**明确声明：Kafka、ClickHouse、Flink 未下载、未部署、未启动；没有集成测试结果，也没有端到端结果。** 具体命令与输出见 [验证报告](docs/verification-2026-11-04.md)。

## 5. 环境变量

复制 `.env.example` 为 `.env` 后按需修改。`.env` 已被 `.gitignore` 忽略，**不得提交任何真实密码、Token 或密钥**。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_ENV` | `dev` | 运行环境 |
| `DEMO_MODE` | `false` | `true` 时使用 SQLite 种子与演示大屏夹具，不连接 Kafka / ClickHouse / MySQL；禁止在生产开启 |
| `METADATA_DB_URL` | `sqlite:///./data-platform.db` | 元数据库（部署切 MySQL） |
| `JWT_SECRET` | 空 | 校验 ai-assistant 签发的 HS256 Token |
| `DP_SECRET_KEY` | 空 | 数据源密码加密的 Fernet key |
| `EVENT_BUS` | `memory` | `memory` / `kafka`；`memory` 时手工运行返回 `published=false` |
| `OLAP_STORE` | `memory` | `memory` / `clickhouse` |
| `KAFKA_BOOTSTRAP` | `127.0.0.1:9092` | Kafka 地址 |
| `KAFKA_CONSUMER_GROUP` | `dp-ods-loader` | 消费组 |
| `KAFKA_TOPIC_PREFIX` | `dp.ods` | ODS Topic 前缀 |
| `CLICKHOUSE_URL` | `http://127.0.0.1:8123` | ClickHouse HTTP 地址 |
| `CLICKHOUSE_DATABASE` | `data_platform` | ClickHouse 库名 |
| `ALERT_WEBHOOK_URL` | 空 | 通用 Webhook，空则不发通知 |
| `DP_ENABLE_SCHEDULER` | `false` | 为 `true` 时 API 进程内启动同步调度器 |
| `SCREEN_TREND_DAYS` / `SCREEN_MAX_TREND_DAYS` | `30` / `365` | 趋势窗口与上限 |
| `SCREEN_RANKING_LIMIT` / `SCREEN_MAX_RANKING_LIMIT` | `10` / `100` | 排行条数与上限 |
| `SCREEN_RECENT_ALERT_LIMIT` | `10` | 大屏实时告警条数 |
| `SCREEN_STREAM_INTERVAL_SECONDS` | `5` | SSE 推送间隔 |

前端变量见 `web/.env.example`：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api` | 后端前缀 |
| `VITE_USE_MOCK` | `false` | `true` 时使用 `src/mock/` 夹具，不访问后端 |
| `VITE_API_PROXY` | `http://127.0.0.1:8000` | 开发代理目标 |

`DP_SECRET_KEY` 必须是 Fernet key（`Fernet.generate_key()` 的输出），缺失时保存数据源密码会失败；接口只返回密文状态，不返回明文。

## 6. 运行入口

### 6.1 零基础设施演示（DEMO_MODE）

只需 Python 与 Node，不启动 Kafka、ClickHouse 或业务库：

```bash
# data-platform/ 目录
python -m venv .venv
.venv/Scripts/pip install -e .        # Windows
# .venv/bin/pip install -e .          # macOS / Linux

# .env（已被 .gitignore 忽略）
DEMO_MODE=true

.venv/Scripts/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# 另一个终端
cd web
npm install
npm run dev
```

浏览器打开 `http://127.0.0.1:5173/screen`。`DEMO_MODE` 的行为边界：

- 首次启动时，仅当元数据库完全为空才写入种子：1 个演示数据源、7 个同步任务、运行记录、5 条质量规则与结果、4 条告警规则与 4 条告警记录、3 张表元数据。已有任何元数据时不写入，避免与真实数据混用。
- 大屏使用按平台日期生成的确定性夹具，同一天重复刷新结果稳定；不查询 ClickHouse。
- 手工运行同步任务返回 `published=false`、`rows_written=0` 和演示提示，不推进水位；手工执行质量规则写入 SQLite 模拟结果，返回 `olap_written=false`、`store_kind=demo`。
- 连接测试与元数据采集直接返回演示提示，不拨号真实数据源。
- 治理接口在演示模式下跳过 JWT 校验，仅用于本机展示；生产环境必须保持 `DEMO_MODE=false`，并由 ai-assistant 签发 `identity=staff` 的 Token。
- 演示数据仅用于界面与流程展示，**不得作为数据准确性或链路可用性的证据**。

### 6.2 真实链路入口

Kafka、ClickHouse、Flink 仍未下载或部署，以下命令仅作为部署参考：

```bash
# 后端 API（在 data-platform/ 目录）
uvicorn app.main:app --host 127.0.0.1 --port 8000

# 常驻消费进程（Kafka + ClickHouse 依赖需先安装并可用）
python -m etl.consumer_main

# 手工执行转换（OLAP_STORE=memory 时仅记录 SQL，不写 ClickHouse）
python -m etl.transform_main --stage ddl
python -m etl.transform_main --stage dwd --ds 2026-11-04
python -m etl.transform_main --stage ads --ds 2026-11-04

# 前端开发服务器（需先 npm install）
cd web && npm run dev

# 前端无后端预览
VITE_USE_MOCK=true npm run dev
```

可选依赖组：`kafka`（confluent-kafka）、`clickhouse`（clickhouse-connect），未安装时工厂会按需报错，单元测试仍可运行。

## 7. API 一览

统一前缀 `/api`，响应 `{code, message, data}`；管理接口要求 `Authorization: Bearer <JWT>` 且 `identity=staff`，大屏接口只读。`DEMO_MODE=true` 时管理接口跳过 JWT 校验并使用模拟执行结果。未捕获异常统一返回 `{code: 500, message: "internal error", data: null}`，堆栈只写入服务端日志。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查 |
| GET/POST | `/datasources` | 数据源列表 / 新建 |
| PUT/DELETE | `/datasources/{id}` | 更新 / 删除 |
| POST | `/datasources/{id}/test` | 连接测试，并回写 `status` |
| POST | `/datasources/{id}/introspect` | 元数据采集 |
| GET/POST | `/sync-tasks` | 同步任务列表 / 新建（仅 7 张注册源表） |
| PUT/DELETE | `/sync-tasks/{id}` | 更新 / 删除 |
| POST | `/sync-tasks/{id}/run` | 手工触发；`EVENT_BUS=memory` 时 `published=false`、`rows_written=0`；`DEMO_MODE` 时返回模拟结果 |
| GET | `/sync-runs` | 运行记录 |
| GET | `/metadata/tables` | 表元数据 |
| GET | `/metadata/tables/{id}/columns` | 字段元数据 |
| GET/POST | `/quality/rules` | 质量规则 |
| PUT/DELETE | `/quality/rules/{id}` | 更新 / 删除 |
| POST | `/quality/rules/{id}/run` | 手工执行；`OLAP_STORE=memory` 时 `olap_written=false`；`DEMO_MODE` 时返回模拟结果 |
| GET | `/quality/results` | 质量结果 |
| GET/POST | `/alerts/rules` | 告警规则 |
| PUT/DELETE | `/alerts/rules/{id}` | 更新 / 删除 |
| GET | `/alerts` | 告警列表 |
| POST | `/alerts/{id}/ack` | 认领告警 |
| POST | `/alerts/{id}/close` | 关闭告警 |
| GET | `/screen/overview` | 大屏 KPI |
| GET | `/screen/trend` | 趋势 |
| GET | `/screen/ranking` | 排行 |
| GET | `/screen/quality` | 质量与链路健康 |
| GET | `/screen/stream` | SSE 推送 |

## 8. 安全与隐私

- 原始 PII（患者姓名、手机号、身份证号、聊天正文 / 引用内容）不进入 ODS / DWD / ADS / Kafka / DLT；Extractor 在发布前调用 `etl/privacy.py` 脱敏，ODS 只保留 `*_masked`、`content_length`、`has_reference` 等派生物。
- DLT 只发布 `{event_id, source_db, source_table, error, sanitized sample}` 小信封，不转发原始事件字节；隔离样本对非对象行只记录类型名。
- 清洗错误文本不拼接源字段值，避免通过错误信息回显 PII。
- 数据源密码使用 Fernet 加密存储，密钥只来自 `DP_SECRET_KEY`。
- 仓库不提交 `.env`、密钥、证书或本机绝对路径。

## 9. V1 未接线 / 已知风险

**未接线（不要按已自动化理解）：**

- DWD / ADS 转换没有调度器：`python -m etl.transform_main` 是手工入口，需按 DDL → DWD → ADS 顺序执行。
- 质量规则没有 cron 执行器：`dp_quality_rule.cron` 只做存储，V1 仅支持 API 手工执行。
- 告警没有指标采集器、自动评估任务和评估 API 端点：`AlertService.evaluate_rule` 只被代码级调用，Webhook 只在被调用时投递。
- 没有登录 UI：后端只校验 ai-assistant 签发的 JWT，前端未提供登录页。
- 大屏未提供“来源分布”与“AI 类型分布”图表（设计中的数据未建模为 ADS 指标）。
- `EVENT_BUS=memory` / `OLAP_STORE=memory` 只是本地验证替身，不代表 Kafka / ClickHouse 可用。

**已知风险：**

- ClickHouse 不可用时，消费管线仍会把事件写入 DLT 并提交位点，而不是对上游施加背压；DLT 只保留脱敏后的小信封（含错误摘要与样本类型），**不能用于重放原始数据**。ClickHouse 故障期间的数据需要重新从 MySQL 全量同步或按水位补跑。
- 同步任务的 `running` 锁只有正常路径会释放；进程崩溃后需要人工把 `dp_sync_task.running` 置 0 并清空 `locked_at`。
- 增量运行在启动时用源表当前 `MAX(watermark)` 作为上界，运行期间新写入的行留给下一次运行；源库补写旧水位的数据只能依赖 overlap 窗口或全量重跑。
- ODS 可空源列在 DDL 中给了默认值，并启用 `input_format_null_as_default=1`，但列类型与源库不完全匹配时仍可能被隔离或进入 DLT。
- ClickHouse 时区统一为 `Asia/Shanghai`（列类型、会话设置与 SQL 函数），未在真实集群验证。

## 10. 后续人工验收清单

以下步骤来自设计文档 [第 11.3 节](docs/2026-11-04-design.md#113-后续人工验收清单)，**本轮全部未执行**：

1. 启动 Kafka 与 ClickHouse；执行 `python -m etl.transform_main --stage ddl` 建表。
2. 配置只读数据源，运行全量任务，核对 ODS 行数。
3. 修改源表数据，运行增量任务，核对水位推进与幂等。
4. 依次执行 `--stage dwd --ds YYYY-MM-DD` 与 `--stage ads --ds YYYY-MM-DD`，核对 DWD / ADS 结果。
5. 手工执行一条质量规则，确认失败结果、告警记录与 Webhook。
6. 打开大屏，确认 KPI、趋势、排行与 SSE 刷新。
7. 启动 Flink 作业，核对实时聚合结果。

任何未执行的步骤不得在简历或汇报中描述为已验证。
