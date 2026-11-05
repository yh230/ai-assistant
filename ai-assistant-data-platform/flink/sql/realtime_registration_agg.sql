-- Realtime registration aggregation, Flink SQL equivalent of
-- flink/jobs/realtime_registration_agg.py.
--
-- Input:  Kafka `ai.registration.event` (ai-assistant event envelope)
-- Window: 5-minute tumbling window, 30-second watermark lag
-- Output: ClickHouse `data_platform.ads_realtime_registration_5m`
--
-- UNVERIFIED: the statements below have not been submitted to a Flink cluster
-- in this delivery round (design doc sections 2.2 and 11.2).

-- Same 10-second checkpoint interval as StreamExecutionEnvironment.enable_checkpointing(10_000).
SET 'execution.checkpointing.interval' = '10 s';

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
);

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
);

INSERT INTO ads_realtime_registration_5m
SELECT window_start, window_end,
       CAST(JSON_VALUE(payload, '$.hospitalId') AS BIGINT) AS hospital_id,
       eventType AS event_type,
       COUNT(*) AS cnt
FROM TABLE(TUMBLE(TABLE registration_events, DESCRIPTOR(event_time), INTERVAL '5' MINUTES))
WHERE eventType IN ('APPOINTMENT_CREATED', 'APPOINTMENT_CANCELLED', 'REGISTRATION_REFUNDED')
GROUP BY window_start, window_end, eventType,
         CAST(JSON_VALUE(payload, '$.hospitalId') AS BIGINT);
