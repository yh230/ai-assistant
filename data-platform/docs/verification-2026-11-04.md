# data-platform 验证报告（2026-11-04）

| 项 | 内容 |
| --- | --- |
| 验证日期 | 2026-11-04 |
| 验证范围 | Python 静态编译、纯逻辑单元测试、前端图表配置单测、Flink 作业语法编译；追加 DEMO_MODE 运行时验收 |
| 运行环境 | Python 3.12.10；Node.js v24.18.0；Windows |
| 基础设施 | Kafka、ClickHouse、Flink 均未下载、未部署、未启动 |
| 结论 | 真实链路未验证；DEMO_MODE 已用 SQLite 种子完成 API、大屏与治理页面运行时验收（见 §1.3） |

所有命令均在 `data-platform/` 目录下执行，下文路径均为相对路径。

## 1. 已执行命令与真实结果

| # | 命令 | 退出码 | 真实输出摘要 |
| --- | --- | --- | --- |
| 1 | `python -m compileall -q app etl tests` | 0 | 无输出 |
| 2 | `python -m unittest discover -s tests/unit -v` | 0 | `Ran 468 tests in 0.050s` / `OK (skipped=166)` |
| 3 | `node --test web/tests/chartOptions.test.mjs` | 0 | `tests 7` / `pass 7` / `fail 0` / `skipped 0` |
| 4 | `python -m py_compile flink/jobs/realtime_registration_agg.py` | 0 | 无输出（仅字节码编译，不导入 PyFlink） |

### 1.1 单元测试计数

- 总计：468 个测试。
- 执行并通过：302 个（468 − 166）。
- 跳过：166 个，均为第三方依赖缺失，不构成失败。
- 失败：0 个。

跳过原因分布（实际输出统计）：

| 跳过原因 | 数量 |
| --- | --- |
| `backend dependencies not installed` | 100 |
| `sqlalchemy not installed` | 34 |
| `sqlalchemy/pydantic_settings not installed` | 26 |
| `cryptography not installed` | 3 |
| `pydantic-settings not installed` | 2 |
| `missing optional dependencies: apscheduler, sqlalchemy, pydantic_settings` | 1 |
| 合计 | 166 |

依赖探测结果：`fastapi`、`httpx`、`sqlalchemy`、`pydantic_settings`、`apscheduler`、`cryptography`、`jwt`、`confluent_kafka`、`clickhouse_connect`、`pyflink` 全部缺失。因此通过的 302 个测试只覆盖清洗、脱敏、源表契约、水位、事件信封、Extractor、消费管线（内存 Fake）、DDL 拆分、质量规则生成、告警判定、大屏服务层、实时事件与 SQL 文件结构等纯逻辑，**不代表 FastAPI 路由、SQLAlchemy 模型、加解密或任何外部系统可用**。

### 1.2 静态编译

`compileall` 只验证语法与可编译性，不导入模块，也不验证运行时行为。`py_compile` 对 Flink 作业同理：它证明文件语法正确，不证明作业能提交或运行。

### 1.3 DEMO_MODE 追加验收（2026-11-04）

真实 Kafka / ClickHouse / Flink 仍未安装或启动。本节只覆盖 `DEMO_MODE=true` 的零基础设施演示路径：

| # | 命令 / 操作 | 结果 |
| --- | --- | --- |
| 1 | 创建 `.venv` 并 `pip install -e .`（仅核心依赖，不含 kafka / clickhouse 可选组） | 成功；FastAPI 0.162.0、SQLAlchemy 2.1.8 |
| 2 | `.venv/Scripts/python -m unittest discover -s tests/unit` | `Ran 491 tests` / `OK` / 0 skipped |
| 3 | `npm install`（官方 registry）与 `npm test` | 7 个 node 测试全部通过 |
| 4 | `npm run build` | `✓ built in 9.80s` |
| 5 | `DEMO_MODE=true` 启动 `uvicorn app.main:app` | `/api/health` 返回 `code=0` |
| 6 | `GET /api/screen/overview` | 6 个 KPI 均为非零演示值（挂号 1056、AI 会话 503、质量分 0.988、延迟 12s、告警 2、取消率 4.40%） |
| 7 | `GET /api/screen/trend?days=3` / `GET /api/screen/quality` | 趋势与质量/告警卡片返回种子数据 |
| 8 | `POST /api/sync-tasks/1/run?mode=INCREMENTAL` | `published=false`、`rows_written=0`、`demo=true`、带演示提示 |
| 9 | `POST /api/quality/rules/2/run` | 写入模拟结果，`olap_written=false`、`store_kind=demo` |
| 10 | 无 JWT 访问 `/api/datasources`、`/api/sync-tasks`、`/api/quality/rules`、`/api/alerts` | 演示模式放行，返回种子数据 |
| 11 | 浏览器打开 `http://127.0.0.1:5173/screen` 及治理四页 | 大屏 SSE 显示“实时流已连接”，KPI/趋势/排行/告警均有数据；治理页表格与操作按钮正常渲染 |

**边界：** 上述结果只证明演示路径可启动、可渲染、可交互；种子数据和演示大屏值均为展示夹具，不是业务数据正确性证据。真实 MySQL 抽取、Kafka 收发、ClickHouse 写入与 Flink 作业仍未验证。

## 2. 未执行项（不得描述为已验证）

| 项 | 状态 | 原因 | 后续人工验收入口 |
| --- | --- | --- | --- |
| Kafka Producer / Consumer、位点提交、DLT | 未执行 | 未下载、未部署 Kafka，客户端为可选依赖且未安装 | 设计文档 §11.3 第 1、3 项 |
| ClickHouse 建表、批量写入、`ReplacingMergeTree` 去重 | 未执行 | 未下载、未部署 ClickHouse | §11.3 第 1、2 项 |
| Flink 镜像构建、作业提交、窗口结果 | 未执行 | 未安装 PyFlink，未启动 Flink 集群；仅执行了 `py_compile` | §11.3 第 6 项 |
| 集成测试 | 未执行 | `tests/integration/` 仅保留说明文件，无测试代码、无基础设施 | §11.3 第 1–6 项 |
| 端到端测试（含大屏 SSE 实时推送） | 未执行 | 无 Kafka / ClickHouse / 后端运行环境 | §11.3 第 5 项 |
| 前端构建（`npm install`、`vite build`） | 未执行 | 按要求不执行 `npm install` / `vite build` | §11.3 第 5 项 |
| 后端进程（`uvicorn app.main:app`）与 TestClient | 未执行 | FastAPI 等依赖未安装 | §11.3 第 1–5 项 |
| 真实 MySQL 抽取与元数据采集 | 未执行 | 未配置只读数据源 | §11.3 第 2、3 项 |

本报告中没有集成项被标记为“通过”，后续任何汇报也不得将上述项目描述为已验证。

## 3. 文档纠偏（本轮完成）

- 设计文档 §8：告警规则路径 `/alert/rules` 改为 `/alerts/rules`，与实现的路由前缀 `alerts` 一致。
- 设计文档 §6.2：常驻进程入口 `python -m etl.consumer` 改为 `python -m etl.consumer_main`。
- 设计文档 §5.2、§7.2：OPS 层补充 `ops_registration_event`，并写明 `ops_quarantine` 以 `(source_table, _event_id, batch_id)` 去重、`batch_id` 标识隔离批次。
- 设计文档 §10：工程结构补充 `etl/consumer_main.py`。

已知仍存在的文档差异（本轮未改动，留待后续）：设计文档 §6.2 仍将 `python -m etl.extractor` 描述为手工运行入口，但实现中抽取由 API `/sync-tasks/{id}/run` 或 APScheduler 触发，`etl/extractor.py` 没有 `__main__` 入口。

## 4. 人工验收入口

按设计文档 §11.3 执行，本轮全部未执行：

1. 启动 Kafka 与 ClickHouse，执行建表脚本。
2. 配置只读数据源，运行全量任务，核对 ODS 行数。
3. 修改源表数据，运行增量任务，核对水位推进与幂等。
4. 触发一条质量规则失败，确认告警记录与 Webhook。
5. 打开大屏，确认 KPI、趋势、排行与 SSE 刷新。
6. 启动 Flink 作业，核对实时聚合结果。

任何未执行的步骤不得在简历或汇报中描述为已验证。
