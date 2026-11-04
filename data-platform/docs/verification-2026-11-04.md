# data-platform 验证报告（2026-11-04）

| 项 | 内容 |
| --- | --- |
| 验证日期 | 2026-11-04 |
| 验证范围 | Python 静态编译、纯逻辑单元测试、前端图表配置单测、Flink 作业语法编译 |
| 运行环境 | Python 3.12.10；Node.js v24.18.0；Windows |
| 基础设施 | Kafka、ClickHouse、Flink 均未下载、未部署、未启动 |
| 结论 | 代码级交付；只有静态检查与纯逻辑单测结果，**没有任何集成、E2E 或运行时证据** |

所有命令均在 `data-platform/` 目录下执行，下文路径均为相对路径。

## 1. 已执行命令与真实结果

| # | 命令 | 退出码 | 真实输出摘要 |
| --- | --- | --- | --- |
| 1 | `python -m compileall -q app etl tests` | 0 | 无输出 |
| 2 | `python -m unittest discover -s tests/unit -v` | 0 | `Ran 407 tests in 0.039s` / `OK (skipped=155)` |
| 3 | `node --test web/tests/chartOptions.test.mjs` | 0 | `tests 6` / `pass 6` / `fail 0` / `skipped 0` |
| 4 | `python -m py_compile flink/jobs/realtime_registration_agg.py` | 0 | 无输出（仅字节码编译，不导入 PyFlink） |

### 1.1 单元测试计数

- 总计：407 个测试。
- 执行并通过：252 个（407 − 155）。
- 跳过：155 个，均为第三方依赖缺失，不构成失败。
- 失败：0 个。

跳过原因分布（实际输出统计）：

| 跳过原因 | 数量 |
| --- | --- |
| `backend dependencies not installed` | 96 |
| `sqlalchemy not installed` | 27 |
| `sqlalchemy/pydantic_settings not installed` | 26 |
| `cryptography not installed` | 3 |
| `pydantic-settings not installed` | 2 |
| `missing optional dependencies: apscheduler, sqlalchemy, pydantic_settings` | 1 |
| 合计 | 155 |

依赖探测结果：`fastapi`、`httpx`、`sqlalchemy`、`pydantic_settings`、`apscheduler`、`cryptography`、`jwt`、`confluent_kafka`、`clickhouse_connect`、`pyflink` 全部缺失。因此通过的 252 个测试只覆盖清洗、水位、事件信封、Extractor、消费管线（内存 Fake）、质量规则生成、告警判定、大屏服务层、实时事件与 SQL 文件结构等纯逻辑，**不代表 FastAPI 路由、SQLAlchemy 模型、加解密或任何外部系统可用**。

### 1.2 静态编译

`compileall` 只验证语法与可编译性，不导入模块，也不验证运行时行为。`py_compile` 对 Flink 作业同理：它证明文件语法正确，不证明作业能提交或运行。

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
