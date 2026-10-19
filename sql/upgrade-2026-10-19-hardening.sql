-- =============================================================
-- P0/P1 共享数据库变更（可重复执行，幂等）
-- 适用库：system_db / hospital_db / registration_db / chat_db
-- 说明：
--   1. 全新环境直接执行 sql/init.sql 即可（已包含下列全部列与索引），无需本脚本。
--   2. 存量环境按顺序执行本脚本；重复执行不会报错（列/索引存在则跳过）。
--   3. 脚本开头的两个检查 SELECT 用于发现重复数据：若结果非空，请先人工清理，
--      否则唯一键 uk_doctor_active_slot / uk_schedule_seq 的创建会失败。
--      建议首次执行前先单独运行这两个 SELECT（预期为空），清理后再执行脚本主体。
--   4. 本脚本只做结构变更，不改动任何业务数据。
--   5. 开头的检查 SELECT 直接引用 registration_db 的表，若目标库/表尚未创建
--      （即全新环境），请跳过本脚本、直接执行 sql/init.sql。
-- =============================================================

-- -------------------------------------------------------------
-- 变更前检查（只读，结果为空才可安全创建唯一键）
-- -------------------------------------------------------------
-- 1) 启用状态下的排班重复槽位（doctor_user_id + schedule_date + period）
SELECT doctor_user_id, schedule_date, period, COUNT(*) AS dup_count
  FROM registration_db.doctor_schedule
 WHERE status = 1
 GROUP BY doctor_user_id, schedule_date, period
HAVING COUNT(*) > 1;

-- 2) 号源重复序号（schedule_id + seq_no）
SELECT schedule_id, seq_no, COUNT(*) AS dup_count
  FROM registration_db.number_source
 GROUP BY schedule_id, seq_no
HAVING COUNT(*) > 1;

-- -------------------------------------------------------------
-- 幂等辅助过程：列 / 索引不存在且表存在时才执行 DDL
-- -------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;

DELIMITER $$

CREATE PROCEDURE add_column_if_missing(
    IN db_name VARCHAR(64), IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(1000))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = db_name AND table_name = tbl)
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = db_name AND table_name = tbl AND column_name = col) THEN
        SET @ddl = CONCAT('ALTER TABLE `', db_name, '`.`', tbl, '` ADD COLUMN ', ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_index_if_missing(
    IN db_name VARCHAR(64), IN tbl VARCHAR(64), IN idx VARCHAR(64), IN ddl VARCHAR(1000))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = db_name AND table_name = tbl)
       AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = db_name AND table_name = tbl AND index_name = idx) THEN
        SET @ddl = CONCAT('ALTER TABLE `', db_name, '`.`', tbl, '` ADD ', ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- -------------------------------------------------------------
-- 1. chat_db.chat_session：多轮对话历史摘要
-- -------------------------------------------------------------
CALL add_column_if_missing('chat_db', 'chat_session', 'summary',
    '`summary` TEXT NULL COMMENT ''历史消息摘要''');
CALL add_column_if_missing('chat_db', 'chat_session', 'summary_updated_at',
    '`summary_updated_at` DATETIME NULL COMMENT ''摘要更新时间''');

-- -------------------------------------------------------------
-- 2. chat_db.knowledge_document：处理失败原因 + 列表查询索引
-- -------------------------------------------------------------
CALL add_column_if_missing('chat_db', 'knowledge_document', 'error_msg',
    '`error_msg` VARCHAR(500) DEFAULT '''' COMMENT ''处理失败原因''');
CALL add_index_if_missing('chat_db', 'knowledge_document', 'idx_hospital_status',
    'KEY `idx_hospital_status` (`hospital_id`, `status`)');

-- -------------------------------------------------------------
-- 3. chat_db.chat_message：按会话取最近消息
-- -------------------------------------------------------------
CALL add_index_if_missing('chat_db', 'chat_message', 'idx_session_created',
    'KEY `idx_session_created` (`session_id`, `created_at`)');

-- -------------------------------------------------------------
-- 4. registration_db.registration：匿名预约幂等键 + 过期扫描/去重查询索引
--    说明：MySQL 唯一索引允许多个 NULL，历史无幂等键的记录不受影响。
-- -------------------------------------------------------------
CALL add_column_if_missing('registration_db', 'registration', 'idempotency_key',
    '`idempotency_key` VARCHAR(64) DEFAULT NULL COMMENT ''匿名预约幂等键''');
CALL add_index_if_missing('registration_db', 'registration', 'uk_registration_idem',
    'UNIQUE KEY `uk_registration_idem` (`idempotency_key`)');
CALL add_index_if_missing('registration_db', 'registration', 'idx_expire_status',
    'KEY `idx_expire_status` (`expire_time`, `status`)');
CALL add_index_if_missing('registration_db', 'registration', 'idx_patient_day',
    'KEY `idx_patient_day` (`patient_phone`, `patient_id_card`, `schedule_date`)');

-- -------------------------------------------------------------
-- 5. registration_db.doctor_schedule：启用排班唯一槽位（生成列 + 唯一键）
--    生成列在 status=1 时取 doctor_user_id-schedule_date-period，停用时为 NULL，
--    因此停用排班不参与唯一性约束。需先清理重复启用排班（见脚本开头检查 1）。
-- -------------------------------------------------------------
CALL add_column_if_missing('registration_db', 'doctor_schedule', 'active_slot',
    '`active_slot` VARCHAR(128) GENERATED ALWAYS AS (IF(`status` = 1, CONCAT(`doctor_user_id`, ''-'', `schedule_date`, ''-'', `period`), NULL)) STORED COMMENT ''启用排班唯一槽位''');
CALL add_index_if_missing('registration_db', 'doctor_schedule', 'uk_doctor_active_slot',
    'UNIQUE KEY `uk_doctor_active_slot` (`active_slot`)');

-- -------------------------------------------------------------
-- 6. registration_db.number_source：号源池唯一序号
--    需先清理重复号源（见脚本开头检查 2）。
-- -------------------------------------------------------------
CALL add_index_if_missing('registration_db', 'number_source', 'uk_schedule_seq',
    'UNIQUE KEY `uk_schedule_seq` (`schedule_id`, `seq_no`)');

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;

-- -------------------------------------------------------------
-- 事后自检：应返回 5 行新增列
-- -------------------------------------------------------------
SELECT table_schema, table_name, column_name, column_type
  FROM information_schema.columns
 WHERE (table_schema = 'chat_db'         AND table_name = 'chat_session'      AND column_name IN ('summary', 'summary_updated_at'))
    OR (table_schema = 'chat_db'         AND table_name = 'knowledge_document' AND column_name = 'error_msg')
    OR (table_schema = 'registration_db' AND table_name = 'registration'       AND column_name = 'idempotency_key')
    OR (table_schema = 'registration_db' AND table_name = 'doctor_schedule'    AND column_name = 'active_slot')
 ORDER BY table_schema, table_name, column_name;

-- -------------------------------------------------------------
-- 事后自检：应返回 7 行新增索引
-- -------------------------------------------------------------
SELECT table_schema, table_name, index_name, non_unique
  FROM information_schema.statistics
 WHERE (table_schema = 'registration_db' AND index_name IN ('uk_registration_idem', 'idx_expire_status', 'idx_patient_day', 'uk_doctor_active_slot', 'uk_schedule_seq'))
    OR (table_schema = 'chat_db'         AND index_name IN ('idx_hospital_status', 'idx_session_created'))
 GROUP BY table_schema, table_name, index_name, non_unique
 ORDER BY table_schema, table_name, index_name;
