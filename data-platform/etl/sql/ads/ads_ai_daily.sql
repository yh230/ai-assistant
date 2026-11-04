INSERT INTO data_platform.ads_ai_daily
SELECT toDate(s.created_at) AS metric_date, s.hospital_id,
       uniqExact(s.session_id) AS session_count,
       countIf(m.role <> '') AS message_count,
       countIf(m.role = 'assistant' AND m.has_reference = 1) / nullIf(countIf(m.role = 'assistant'), 0) AS rag_hit_rate,
       now() AS updated_at
FROM data_platform.dwd_ai_session s
LEFT JOIN data_platform.dwd_ai_message m ON m.session_id = s.session_id
WHERE toDate(s.created_at) = '{{ds}}'
GROUP BY toDate(s.created_at), s.hospital_id;
