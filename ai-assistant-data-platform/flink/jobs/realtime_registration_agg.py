"""Realtime registration aggregation job (code showcase).

Consumes ``ai.registration.event`` from Kafka, aggregates appointment events in
5-minute tumbling windows per hospital and event type, and sinks the result to
``data_platform.ads_realtime_registration_5m`` over JDBC.

Status: code implemented, **not deployed and not runtime-verified**.  No Flink
cluster, Kafka broker or ClickHouse instance was started for this delivery
(design doc sections 2.2 and 11.2).
"""

from pyflink.datastream import StreamExecutionEnvironment
from pyflink.table import EnvironmentSettings, StreamTableEnvironment


def main() -> None:
    env = StreamExecutionEnvironment.get_execution_environment()
    env.enable_checkpointing(10_000)
    table_env = StreamTableEnvironment.create(
        env, environment_settings=EnvironmentSettings.in_streaming_mode()
    )

    table_env.execute_sql(
        """
        CREATE TABLE registration_events (
          eventId STRING,
          eventType STRING,
          occurredAt BIGINT,
          payload STRING,
          event_time AS TO_TIMESTAMP_LTZ(occurredAt, 3),
          WATERMARK FOR event_time AS event_time - INTERVAL '30' SECOND
        ) WITH (
          'connector' = 'kafka',
          'topic' = 'ai.registration.event',
          'properties.bootstrap.servers' = '127.0.0.1:9092',
          'properties.group.id' = 'dp-flink-rt-agg',
          'scan.startup.mode' = 'latest-offset',
          'format' = 'json',
          'json.fail-on-missing-field' = 'false',
          'json.ignore-parse-errors' = 'true'
        )
        """
    )

    table_env.execute_sql(
        """
        CREATE TABLE ads_realtime_registration_5m (
          window_start TIMESTAMP(3),
          window_end TIMESTAMP(3),
          hospital_id BIGINT,
          event_type STRING,
          cnt BIGINT
        ) WITH (
          'connector' = 'jdbc',
          'url' = 'jdbc:clickhouse://127.0.0.1:8123/data_platform',
          'table-name' = 'ads_realtime_registration_5m',
          'username' = 'default',
          'password' = ''
        )
        """
    )

    table_env.execute_sql(
        """
        INSERT INTO ads_realtime_registration_5m
        SELECT window_start, window_end,
               CAST(JSON_VALUE(payload, '$.hospitalId') AS BIGINT) AS hospital_id,
               eventType AS event_type,
               COUNT(*) AS cnt
        FROM TABLE(TUMBLE(TABLE registration_events, DESCRIPTOR(event_time), INTERVAL '5' MINUTES))
        WHERE eventType IN ('APPOINTMENT_CREATED', 'APPOINTMENT_CANCELLED', 'REGISTRATION_REFUNDED')
        GROUP BY window_start, window_end, eventType,
                 CAST(JSON_VALUE(payload, '$.hospitalId') AS BIGINT)
        """
    )


if __name__ == "__main__":
    main()
