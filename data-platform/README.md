# 医院数据治理与运营大屏平台（data-platform）

> 状态：**代码级交付**。Kafka、ClickHouse、Flink 未下载、未部署、未启动；集成测试、E2E 与前端构建均未执行。
> 本仓库任何内容都不代表端到端已通过，详见 [验证报告](docs/verification-2026-11-04.md)。

## 1. 项目定位

`data-platform` 是 ai-assistant 的**下游数据平台**，不修改 ai-assistant 现有服务，只读接入其业务库：

- 对 `system_db`、`hospital_db`、`registration_db`、`chat_db` 做全量 + 增量抽取，按水位断点续跑。
- 消费 ai-assistant 现有 `ai.registration.event` 实时事件，与历史回补数据统一入仓。
- 在 ClickHouse 内完成 ODS / DWD / ADS 分层建模与去重。
- 提供元数据浏览、6 类标准质量规则（另支持自定义 SQL）、质量评分、告警闭环与 Webhook。
- 提供 1920×1080 运营大屏与治理后台，前端可用 `VITE_USE_MOCK=true` 无后端预览。
- 交付 PyFlink 实时聚合作业代码与 Flink SQL：**代码已实现，未部署、未运行验证**。

设计文档：[docs/2026-11-04-design.md](docs/2026-11-04-design.md)；实施计划：[docs/plans/2026-11-04-data-platform.md](docs/plans/2026-11-04-data-platform.md)。

## 2. 架构

```text
ai-assistant MySQL（只读）
  system_db / hospital_db / registration_db / chat_db
        │  全量 keyset 分页 + update_time 增量（水位 / 断点）
        ▼
   Extractor ──────────────────────────────► Kafka `dp.ods.<db>.<table>`
                                                   │
   ai.registration.event ──┬──────────────────────┤
                           │                      ▼
                           │              Consumer（批量 / 清洗 / 幂等 / 隔离）
                           │                      │
                           │                      ▼
                           │              ClickHouse ODS ──► DWD ──► ADS
                           │                      │
                           │                      ├──► 质量规则 / 告警引擎 ──► 站内告警 + Webhook
                           │                      └──► FastAPI `/api/screen`（SSE 5s）
                           │                                   │
                           └──► Flink（代码展示，未部署）        ▼
                                 └──► ads_realtime_registration_5m   Vue 3 + ECharts 大屏 / 治理后台
```

端口与适配器：`EventPublisher` / `EventConsumer`、`OlapStore`、`SourceRepository` 均以接口隔离，业务层只依赖端口；生产实现为 Kafka / ClickHouse / MySQL，单元测试使用内存 Fake，因此缺少外部依赖时仍可完成静态检查与纯逻辑单测。

## 3. 目录说明

```text
data-platform/
├── app/                      # FastAPI 应用
│   ├── main.py               # 应用入口、异常处理、路由注册
│   ├── core/                 # 配置、数据库、加解密、统一响应、异常
│   ├── api/                  # 数据源、元数据、同步、质量、告警、大屏路由
│   ├── models/               # SQLAlchemy 元数据模型
│   ├── schemas/              # Pydantic 请求/响应模型
│   ├── services/             # 治理、质量、告警、大屏业务逻辑
│   └── scheduler.py          # APScheduler 入口（需 DP_ENABLE_SCHEDULER=true）
├── etl/                      # 数据工程核心
│   ├── ports/                # EventBus / OlapStore / SourceRepository / RunStore 接口
│   ├── adapters/             # Kafka、ClickHouse、MySQL 与内存 Fake 实现
│   ├── extractor.py          # 全量 + 增量抽取
│   ├── consumer.py           # 批量消费、清洗、隔离（由 consumer_main 驱动）
│   ├── consumer_main.py      # 常驻消费进程入口
│   ├── cleaning.py           # 清洗与脱敏
│   ├── transform.py          # DWD / ADS SQL 执行
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
| 数据源 CRUD、连接测试、元数据采集、密码 Fernet 加密 | 是 | Python 编译；加解密 / 服务层测试因依赖缺失 SKIP | MySQL 连接与采集 |
| 同步任务、运行记录、全量 / 增量抽取、水位断点 | 是 | 水位 / Extractor 共 14 项单测执行；服务层 / API 测试 SKIP | 真实 MySQL 抽取 |
| Kafka 事件信封、Producer / Consumer 适配器 | 是 | 信封、消费管线（内存 Fake）单测执行；适配器仅编译 | 真实 Kafka 收发、位点提交、DLT |
| 清洗、脱敏、隔离、实时预约事件 | 是 | 清洗 / 消费 / 实时事件单测执行 | 真实数据与 ClickHouse 写入 |
| ClickHouse ODS / DWD / ADS / OPS DDL 与转换 SQL | 是 | SQL 文件结构 13 项单测执行 | DDL 执行、`ReplacingMergeTree` 去重 |
| 质量规则（6 类 + 自定义 SQL）、评分 | 是 | 规则生成 / 评分单测执行；服务层 / API 测试 SKIP | 在 ClickHouse 执行 |
| 告警规则、去重、冷却、Webhook | 是 | 判定 / 指纹 / 冷却 / Webhook 发送逻辑单测执行；服务层 / API 测试 SKIP | Webhook 实际投递 |
| 大屏 API 与 SSE | 是 | 大屏服务层与仓库单测执行；路由 / SSE 测试 SKIP | 真实查询与 SSE 推送 |
| Vue 3 大屏与治理后台 | 是 | `node --test` 图表配置 6 项执行 | `npm install` / `npm run build` / 浏览器验收 |
| Flink 实时聚合 | 是（代码展示） | `py_compile` 退出码 0 | 镜像构建、作业提交、结果查询 |
| APScheduler 调度 | 是 | 仅 Python 编译；构建与 cron 测试 SKIP | 真实定时触发 |

**明确声明：Kafka、ClickHouse、Flink 未下载、未部署、未启动；没有集成测试结果，也没有端到端结果。** 具体命令与输出见 [验证报告](docs/verification-2026-11-04.md)。

## 5. 环境变量

复制 `.env.example` 为 `.env` 后按需修改。`.env` 已被 `.gitignore` 忽略，**不得提交任何真实密码、Token 或密钥**。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_ENV` | `dev` | 运行环境 |
| `METADATA_DB_URL` | `sqlite:///./data-platform.db` | 元数据库（部署切 MySQL） |
| `SOURCE_DB_URL` | 空 | 只读业务库连接串 |
| `JWT_SECRET` | 空 | 校验 ai-assistant 签发的 HS256 Token |
| `DP_SECRET_KEY` | 空 | 数据源密码加密的 Fernet key |
| `EVENT_BUS` | `memory` | `memory` / `kafka` |
| `OLAP_STORE` | `memory` | `memory` / `clickhouse` |
| `KAFKA_BOOTSTRAP` | `127.0.0.1:9092` | Kafka 地址 |
| `KAFKA_CONSUMER_GROUP` | `dp-ods-loader` | 消费组 |
| `KAFKA_TOPIC_PREFIX` | `dp.ods` | ODS Topic 前缀 |
| `CLICKHOUSE_URL` | `http://127.0.0.1:8123` | ClickHouse HTTP 地址 |
| `CLICKHOUSE_DATABASE` | `data_platform` | ClickHouse 库名 |
| `ALERT_WEBHOOK_URL` | 空 | 通用 Webhook，空则不发通知 |
| `DP_ENABLE_SCHEDULER` | `false` | 为 `true` 时 API 进程内启动调度器 |
| `TIMEZONE` | `Asia/Shanghai` | 平台统一时间口径 |
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

本轮未启动任何进程，以下命令仅作为部署参考：

```bash
# 后端 API（在 data-platform/ 目录）
uvicorn app.main:app --host 127.0.0.1 --port 8000

# 常驻消费进程（Kafka + ClickHouse 依赖需先安装并可用）
python -m etl.consumer_main

# 前端开发服务器（需先 npm install）
cd web && npm run dev

# 前端无后端预览
VITE_USE_MOCK=true npm run dev
```

可选依赖组：`kafka`（confluent-kafka）、`clickhouse`（clickhouse-connect），未安装时工厂会按需报错，单元测试仍可运行。

## 7. API 一览

统一前缀 `/api`，响应 `{code, message, data}`；管理接口要求 `Authorization: Bearer <JWT>` 且 `identity=staff`，大屏接口只读。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查 |
| GET/POST | `/datasources` | 数据源列表 / 新建 |
| PUT/DELETE | `/datasources/{id}` | 更新 / 删除 |
| POST | `/datasources/{id}/test` | 连接测试 |
| POST | `/datasources/{id}/introspect` | 元数据采集 |
| GET/POST | `/sync-tasks` | 同步任务列表 / 新建 |
| PUT/DELETE | `/sync-tasks/{id}` | 更新 / 删除 |
| POST | `/sync-tasks/{id}/run` | 手工触发 |
| GET | `/sync-runs` | 运行记录 |
| GET | `/metadata/tables` | 表元数据 |
| GET | `/metadata/tables/{id}/columns` | 字段元数据 |
| GET/POST | `/quality/rules` | 质量规则 |
| PUT/DELETE | `/quality/rules/{id}` | 更新 / 删除 |
| POST | `/quality/rules/{id}/run` | 手工执行 |
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

- 姓名、手机号、身份证号、聊天正文不进入 ODS / DWD / ADS，也不写入日志。
- 数据源密码使用 Fernet 加密存储，密钥只来自 `DP_SECRET_KEY`。
- 日志携带 `run_id` / `task_id` / `event_id`，不打印敏感字段。
- 仓库不提交 `.env`、密钥、证书或本机绝对路径。

## 9. 后续人工验收清单

以下步骤来自设计文档 [第 11.3 节](docs/2026-11-04-design.md#113-后续人工验收清单)，**本轮全部未执行**：

1. 启动 Kafka 与 ClickHouse，执行建表脚本。
2. 配置只读数据源，运行全量任务，核对 ODS 行数。
3. 修改源表数据，运行增量任务，核对水位推进与幂等。
4. 触发一条质量规则失败，确认告警记录与 Webhook。
5. 打开大屏，确认 KPI、趋势、排行与 SSE 刷新。
6. 启动 Flink 作业，核对实时聚合结果。

任何未执行的步骤不得在简历或汇报中描述为已验证。
