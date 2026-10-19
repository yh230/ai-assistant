-- =============================================================
-- P0/P1 共享数据库变更（可重复执行，幂等）
-- 适用库：system_db / hospital_db / registration_db / chat_db
-- 说明：
--   1. 全新环境直接执行 sql/init.sql 即可（已包含下列全部列与索引），无需本脚本。
--   2. 存量环境按顺序执行本脚本；重复执行不会因对象已存在而报错（列/索引存在则跳过）。
--   3. 脚本开头的两个检查 SELECT 用于发现重复数据：若结果非空，请先人工清理，
--      否则唯一键 uk_doctor_active_slot / uk_schedule_seq 的创建会失败。
--      建议首次执行前先单独运行这两个 SELECT（预期为空），清理后再执行脚本主体。
--   4. 若未先清理重复数据，唯一键的 ALTER 会报错，mysql 客户端默认遇错即退出
--      （不会继续执行后续语句）。正确处理方式是：清理重复数据后，重新完整执行本脚本
--      （脚本幂等，已完成的步骤会自动跳过）。
--   5. 本脚本只做结构变更，不改动任何业务数据。
--   6. 创建存储过程需要 CREATE ROUTINE 权限（以及 ALTER / INDEX / ALTER ROUTINE 等），
--      执行账号不足时请先由 DBA 授权；脚本首尾的 DROP PROCEDURE 同样需要该权限。
--   7. 开头的检查 SELECT 直接引用 registration_db 的表，若目标库/表尚未创建
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
-- 注意：CREATE PROCEDURE 必须先有默认库，故先切换到 registration_db
--       （本脚本所有对象均以 库名.表名 全限定方式引用，切换默认库不影响后续 DDL）。
-- -------------------------------------------------------------
USE registration_db;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
DROP PROCEDURE IF EXISTS drop_index_if_exists;

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

CREATE PROCEDURE drop_index_if_exists(
    IN db_name VARCHAR(64), IN tbl VARCHAR(64), IN idx VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.statistics
                WHERE table_schema = db_name AND table_name = tbl AND index_name = idx) THEN
        SET @ddl = CONCAT('ALTER TABLE `', db_name, '`.`', tbl, '` DROP INDEX `', idx, '`');
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
--    同时删除历史遗留的跨状态唯一键 uk_schedule（否则"停用后再建同日同时段排班"
--    仍会被它拦截），并以非唯一查询索引 idx_doctor_date 替代。
--    顺序：先加生成列/唯一键（此时旧唯一键仍在，可双重校验无重复），
--          再删除 uk_schedule，最后补普通索引。
-- -------------------------------------------------------------
CALL add_column_if_missing('registration_db', 'doctor_schedule', 'active_slot',
    '`active_slot` VARCHAR(128) GENERATED ALWAYS AS (IF(`status` = 1, CONCAT(`doctor_user_id`, ''-'', `schedule_date`, ''-'', `period`), NULL)) STORED COMMENT ''启用排班唯一槽位''');
CALL add_index_if_missing('registration_db', 'doctor_schedule', 'uk_doctor_active_slot',
    'UNIQUE KEY `uk_doctor_active_slot` (`active_slot`)');
CALL drop_index_if_exists('registration_db', 'doctor_schedule', 'uk_schedule');
CALL add_index_if_missing('registration_db', 'doctor_schedule', 'idx_doctor_date',
    'KEY `idx_doctor_date` (`doctor_user_id`, `schedule_date`)');

-- -------------------------------------------------------------
-- 6. registration_db.number_source：号源池唯一序号
--    需先清理重复号源（见脚本开头检查 2）。
-- -------------------------------------------------------------
CALL add_index_if_missing('registration_db', 'number_source', 'uk_schedule_seq',
    'UNIQUE KEY `uk_schedule_seq` (`schedule_id`, `seq_no`)');

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
DROP PROCEDURE IF EXISTS drop_index_if_exists;
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
-- 事后自检：应返回 8 行新增索引
-- 以 (table_schema, table_name, index_name) 精确配对匹配，而非仅按 index_name 过滤：
-- 索引名只在表内唯一，idx_doctor_date 同时存在于 registration / number_source，
-- 仅按名字过滤会把这两条无关索引也计入，得到 10 行。
-- （uk_schedule 已被删除，不在预期结果内；idx_doctor_date 为新增的普通索引）
-- information_schema.statistics 每个索引列一行，故用 DISTINCT 去重。
-- -------------------------------------------------------------
SELECT DISTINCT s.table_schema, s.table_name, s.index_name, s.non_unique
  FROM information_schema.statistics s
  JOIN (
              SELECT 'registration_db' AS table_schema, 'doctor_schedule' AS table_name, 'uk_doctor_active_slot' AS index_name
    UNION ALL SELECT 'registration_db', 'doctor_schedule', 'idx_doctor_date'
    UNION ALL SELECT 'registration_db', 'registration', 'uk_registration_idem'
    UNION ALL SELECT 'registration_db', 'registration', 'idx_expire_status'
    UNION ALL SELECT 'registration_db', 'registration', 'idx_patient_day'
    UNION ALL SELECT 'registration_db', 'number_source', 'uk_schedule_seq'
    UNION ALL SELECT 'chat_db', 'knowledge_document', 'idx_hospital_status'
    UNION ALL SELECT 'chat_db', 'chat_message', 'idx_session_created'
       ) e
    ON s.table_schema = e.table_schema
   AND s.table_name   = e.table_name
   AND s.index_name   = e.index_name
 ORDER BY s.table_schema, s.table_name, s.index_name;

-- -------------------------------------------------------------
-- 事后自检：uk_schedule 应已不存在（期望返回 0 行）
-- -------------------------------------------------------------
SELECT table_schema, table_name, index_name
  FROM information_schema.statistics
 WHERE table_schema = 'registration_db'
   AND table_name = 'doctor_schedule'
   AND index_name = 'uk_schedule';
