INSERT INTO data_platform.dwd_registration
SELECT id, registration_no, hospital_id, department_id, doctor_user_id,
       schedule_date, period, source_type, pay_status, pay_amount, status,
       create_time, update_time, _event_id, now('Asia/Shanghai')
FROM data_platform.ods_registration FINAL
WHERE schedule_date = '{{ds}}';
