INSERT INTO data_platform.dwd_ai_session
SELECT id, session_id, hospital_id, session_type, identity, created_at, updated_at, _event_id, now()
FROM data_platform.ods_chat_session FINAL
WHERE toDate(created_at) = '{{ds}}';
