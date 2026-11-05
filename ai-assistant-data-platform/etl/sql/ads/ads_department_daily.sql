INSERT INTO data_platform.ads_department_daily
SELECT schedule_date AS metric_date, hospital_id, department_id,
       count() AS reg_count,
       countIf(status = 3) AS visit_count,
       countIf(status IN (4, 5, 6)) AS cancel_count,
       now('Asia/Shanghai') AS updated_at
FROM data_platform.dwd_registration FINAL
WHERE schedule_date = '{{ds}}'
GROUP BY schedule_date, hospital_id, department_id;
