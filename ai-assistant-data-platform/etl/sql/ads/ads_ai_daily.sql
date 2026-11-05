INSERT INTO data_platform.ads_ai_daily
SELECT toDate(s.created_at, 'Asia/Shanghai') AS metric_date, s.hospital_id,
       uniqExact(s.session_id) AS session_count,
       countIf(m.role <> '') AS message_count,
       ifNull(countIf(m.role = 'assistant' AND m.has_reference = 1) / nullIf(countIf(m.role = 'assistant'), 0), 0) AS rag_hit_rate,
       now('Asia/Shanghai') AS updated_at
FROM data_platform.dwd_ai_session s FINAL
LEFT JOIN data_platform.dwd_ai_message m FINAL ON m.session_id = s.session_id
WHERE toDate(s.created_at, 'Asia/Shanghai') = '{{ds}}'
GROUP BY toDate(s.created_at, 'Asia/Shanghai'), s.hospital_id;
