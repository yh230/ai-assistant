INSERT INTO data_platform.ads_hospital_daily
SELECT
  r.schedule_date AS metric_date,
  r.hospital_id,
  count() AS reg_count,
  sumIf(r.pay_amount, r.pay_status = 1) AS paid_amount,
  ifNull(countIf(r.status IN (4, 5, 6)) / nullIf(count(), 0), 0) AS cancel_rate,
  ifNull(countIf(r.status = 3) / nullIf(countIf(r.status IN (2, 3)), 0), 0) AS visit_rate,
  s.session_count AS ai_sessions,
  s.rag_hit_rate AS rag_hit_rate,
  now() AS updated_at
FROM data_platform.dwd_registration r FINAL
LEFT JOIN (
  SELECT s.hospital_id,
         uniqExact(s.session_id) AS session_count,
         ifNull(countIf(m.role = 'assistant' AND m.has_reference = 1) / nullIf(countIf(m.role = 'assistant'), 0), 0) AS rag_hit_rate
  FROM data_platform.dwd_ai_session s FINAL
  LEFT JOIN data_platform.dwd_ai_message m FINAL ON m.session_id = s.session_id
  WHERE toDate(s.created_at) = '{{ds}}'
  GROUP BY s.hospital_id
) s ON s.hospital_id = r.hospital_id
WHERE r.schedule_date = '{{ds}}'
GROUP BY r.schedule_date, r.hospital_id, s.session_count, s.rag_hit_rate;
