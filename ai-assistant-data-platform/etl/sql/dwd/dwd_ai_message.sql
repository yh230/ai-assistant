INSERT INTO data_platform.dwd_ai_message
SELECT id, session_id, role, content_length, has_reference, created_at, _event_id, now('Asia/Shanghai')
FROM data_platform.ods_chat_message FINAL
WHERE toDate(created_at, 'Asia/Shanghai') = '{{ds}}';
