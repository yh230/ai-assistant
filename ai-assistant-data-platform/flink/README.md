# Flink 实时聚合（代码展示）

状态：代码已实现，**未部署、未运行验证**。

- 输入：Kafka `ai.registration.event`
- 窗口：5 分钟滚动窗口
- 输出：ClickHouse `data_platform.ads_realtime_registration_5m`
- 依赖：Kafka connector、ClickHouse JDBC driver、PyFlink 1.19

## 文件

| 文件 | 说明 |
| --- | --- |
| `jobs/realtime_registration_agg.py` | PyFlink 作业（Table API），与 `sql/` 中的 Flink SQL 等价 |
| `sql/realtime_registration_agg.sql` | 可直接提交给 Flink SQL Client 的等价语句 |
| `Dockerfile` | 基于 `flink:1.19-scala_2.12-java17` 的镜像定义 |

## 口径

- 事件类型过滤：`APPOINTMENT_CREATED`、`APPOINTMENT_CANCELLED`、`REGISTRATION_REFUNDED`。
- 医院维度取自事件 payload 的 `hospitalId`（`JSON_VALUE(payload, '$.hospitalId')`）。
- 水位线：`event_time - INTERVAL '30' SECOND`，checkpoint 间隔 10 秒。
- 输出窗口为 `[window_start, window_end)`，与 `etl/sql/ddl/clickhouse.sql` 中
  `ads_realtime_registration_5m` 的列一一对应，该表只由本作业写入。

## 本地验证步骤（本轮未执行）

1. 启动 Kafka 与 ClickHouse。
2. 构建镜像并提交作业。
3. 查询 `ads_realtime_registration_5m` 确认窗口结果。

参考命令（未在本轮执行，且基础镜像内未内置 connector 与 JDBC driver）：

```bash
docker build -t ai-assistant-data-platform-flink:1.19 ./flink
flink run -py /opt/flink/usrlib/jobs/realtime_registration_agg.py
```

## 已知限制

- 镜像未打包 `flink-sql-connector-kafka` 与 ClickHouse JDBC driver，提交前需放入
  `/opt/flink/lib/`；本轮按要求不下载任何依赖。
- `TO_TIMESTAMP_LTZ` 依赖会话时区，部署时需将 `table.local-time-zone` 设为
  `Asia/Shanghai`，与平台统一时间口径一致。
- 作业仅在代码层面与 DDL 对齐，窗口结果、checkpoint 与 JDBC sink 行为均未实测。
