-- data-platform ClickHouse schema (ODS / DWD / ADS / OPS).
-- UNVERIFIED: code-only delivery. This DDL has not been executed against a
-- live ClickHouse server (design doc section 11.2); syntax and runtime
-- behaviour remain unverified until the manual acceptance checklist is run.
--
-- Deduplication: ODS/DWD tables use ReplacingMergeTree(_ingested_at) keyed by
-- the business key, so repeated loads of the same row converge after merge.
-- OPS tables are replay-safe as well: quarantine is keyed by
-- (source_table, _event_id, batch_id) and audit by (batch_id, source_table),
-- so a replayed batch replaces its rows on merge instead of duplicating them.

CREATE DATABASE IF NOT EXISTS data_platform;

CREATE TABLE IF NOT EXISTS data_platform.ods_registration (
  id UInt64,
  registration_no String,
  hospital_id UInt64,
  department_id UInt64,
  doctor_user_id UInt64,
  schedule_date Date,
  period UInt8,
  patient_name_masked String,
  patient_phone_masked String,
  source_type UInt8,
  pay_status UInt8,
  pay_amount Decimal(10,2),
  status UInt8,
  create_time DateTime,
  update_time DateTime,
  _event_id String,
  _source String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(schedule_date)
ORDER BY (hospital_id, schedule_date, id)
TTL toDateTime(schedule_date) + INTERVAL 2 YEAR;

CREATE TABLE IF NOT EXISTS data_platform.ods_chat_session (
  id UInt64,
  session_id String,
  user_id Nullable(UInt64),
  identity Nullable(String),
  session_type Nullable(String),
  hospital_id Nullable(UInt64),
  role_id Nullable(String),
  created_at Nullable(DateTime),
  updated_at Nullable(DateTime),
  _event_id String,
  _source String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (hospital_id, id)
SETTINGS allow_nullable_key = 1;

CREATE TABLE IF NOT EXISTS data_platform.ods_chat_message (
  id UInt64,
  session_id String,
  role String,
  content_length UInt32,
  has_reference UInt8,
  created_at Nullable(DateTime),
  _event_id String,
  _source String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (session_id, id);

CREATE TABLE IF NOT EXISTS data_platform.ods_doctor_schedule (
  id UInt64,
  hospital_id UInt64,
  department_id UInt64,
  doctor_user_id UInt64,
  schedule_date Date,
  period UInt8,
  total_limit UInt32,
  registered_count UInt32,
  status UInt8,
  update_time DateTime,
  _event_id String,
  _source String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(schedule_date)
ORDER BY (hospital_id, schedule_date, id);

CREATE TABLE IF NOT EXISTS data_platform.ods_number_source (
  id UInt64,
  hospital_id UInt64,
  department_id UInt64,
  doctor_user_id UInt64,
  schedule_id UInt64,
  schedule_date Date,
  period UInt8,
  seq_no UInt32,
  registration_id Nullable(UInt64),
  status UInt8,
  create_time DateTime,
  _event_id String,
  _source String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(schedule_date)
ORDER BY (hospital_id, schedule_date, id);

CREATE TABLE IF NOT EXISTS data_platform.ods_hospital (
  id UInt64,
  name String,
  address String,
  phone String,
  status UInt8,
  update_time DateTime,
  _event_id String,
  _source String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
ORDER BY (id);

CREATE TABLE IF NOT EXISTS data_platform.ods_department (
  id UInt64,
  hospital_id UInt64,
  name String,
  director_user_id Nullable(UInt64),
  status UInt8,
  update_time DateTime,
  _event_id String,
  _source String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
ORDER BY (hospital_id, id);

CREATE TABLE IF NOT EXISTS data_platform.dwd_registration (
  id UInt64,
  registration_no String,
  hospital_id UInt64,
  department_id UInt64,
  doctor_user_id UInt64,
  schedule_date Date,
  period UInt8,
  source_type UInt8,
  pay_status UInt8,
  pay_amount Decimal(10,2),
  status UInt8,
  create_time DateTime,
  update_time DateTime,
  _event_id String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(schedule_date)
ORDER BY (hospital_id, schedule_date, id);

CREATE TABLE IF NOT EXISTS data_platform.dwd_ai_session (
  id UInt64,
  session_id String,
  hospital_id Nullable(UInt64),
  session_type Nullable(String),
  identity Nullable(String),
  created_at Nullable(DateTime),
  updated_at Nullable(DateTime),
  _event_id String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (hospital_id, id)
SETTINGS allow_nullable_key = 1;

CREATE TABLE IF NOT EXISTS data_platform.dwd_ai_message (
  id UInt64,
  session_id String,
  role String,
  content_length UInt32,
  has_reference UInt8,
  created_at Nullable(DateTime),
  _event_id String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (session_id, id);

CREATE VIEW IF NOT EXISTS data_platform.dwd_hospital AS
SELECT id, name, address, phone, status, update_time
FROM data_platform.ods_hospital FINAL WHERE status = 1;

CREATE VIEW IF NOT EXISTS data_platform.dwd_department AS
SELECT id, hospital_id, name, director_user_id, status, update_time
FROM data_platform.ods_department FINAL WHERE status = 1;

CREATE VIEW IF NOT EXISTS data_platform.dwd_schedule AS
SELECT id, hospital_id, department_id, doctor_user_id, schedule_date, period,
       total_limit, registered_count, status, update_time
FROM data_platform.ods_doctor_schedule FINAL;

CREATE TABLE IF NOT EXISTS data_platform.ads_hospital_daily (
  metric_date Date,
  hospital_id UInt64,
  reg_count UInt64,
  paid_amount Decimal(12,2),
  cancel_rate Float64,
  visit_rate Float64,
  ai_sessions UInt64,
  rag_hit_rate Float64,
  updated_at DateTime
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(metric_date)
ORDER BY (metric_date, hospital_id);

CREATE TABLE IF NOT EXISTS data_platform.ads_department_daily (
  metric_date Date,
  hospital_id UInt64,
  department_id UInt64,
  reg_count UInt64,
  visit_count UInt64,
  cancel_count UInt64,
  updated_at DateTime
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(metric_date)
ORDER BY (metric_date, hospital_id, department_id);

CREATE TABLE IF NOT EXISTS data_platform.ads_ai_daily (
  metric_date Date,
  hospital_id Nullable(UInt64),
  session_count UInt64,
  message_count UInt64,
  rag_hit_rate Float64,
  updated_at DateTime
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(metric_date)
ORDER BY (metric_date, hospital_id)
SETTINGS allow_nullable_key = 1;

CREATE TABLE IF NOT EXISTS data_platform.ads_pipeline_health (
  metric_time DateTime,
  source_table String,
  rows_ingested UInt64,
  lag_seconds Int64,
  updated_at DateTime
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(metric_time)
ORDER BY (metric_time, source_table);

-- Task 4 / Task 7 write the quality verdict as a String ("PASS"/"FAIL"/...).
CREATE TABLE IF NOT EXISTS data_platform.ads_quality_daily (
  metric_date Date,
  rule_id UInt64,
  rule_name String,
  total_rows UInt64,
  bad_rows UInt64,
  pass_rate Float64,
  severity String,
  status String,
  checked_at DateTime
) ENGINE = ReplacingMergeTree(checked_at)
PARTITION BY toYYYYMM(metric_date)
ORDER BY (metric_date, rule_id);

-- Quarantined rows are replayed; batch_id is part of the dedupe key so a
-- replayed batch replaces its rows instead of duplicating them.  sample holds
-- sanitized JSON text, never the PII-bearing source row.
CREATE TABLE IF NOT EXISTS data_platform.ops_quarantine (
  _event_id String,
  batch_id String,
  source_table String,
  errors String,
  sample String,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (source_table, _event_id, batch_id);

CREATE TABLE IF NOT EXISTS data_platform.ops_ingest_audit (
  batch_id String,
  source_table String,
  rows_ingested UInt64,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
PARTITION BY toYYYYMM(_ingested_at)
ORDER BY (batch_id, source_table);

-- Written by the Task 5 realtime handler for ai.registration.event.  Columns
-- match etl.realtime.RegistrationEventHandler row for row.
CREATE TABLE IF NOT EXISTS data_platform.ops_registration_event (
  _event_id String,
  event_type String,
  registration_no String,
  hospital_id Nullable(UInt64),
  visit_date Nullable(Date),
  period Nullable(UInt8),
  occurred_at DateTime,
  _ingested_at DateTime
) ENGINE = ReplacingMergeTree(_ingested_at)
ORDER BY (_event_id);

-- Written only by the Flink realtime aggregation job (see flink/).
CREATE TABLE IF NOT EXISTS data_platform.ads_realtime_registration_5m (
  window_start DateTime,
  window_end DateTime,
  hospital_id UInt64,
  event_type String,
  cnt UInt64
) ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(window_start)
ORDER BY (window_start, hospital_id, event_type);
