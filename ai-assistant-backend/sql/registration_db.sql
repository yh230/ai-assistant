-- =============================================================
-- 挂号库（registration-service）：门诊挂号取号模块表结构
-- 依赖：按 ID 引用 system_db.user / hospital_db.department（无外键）
-- 设计：只读人员系统数据（医院/科室/医生绑定在 sys_user 上），本模块只存门诊业务数据。
-- 号源与挂号记录均冗余 hospital_id/department_id/doctor_user_id，便于按数据权限范围过滤与统计，无需跨表 join。
-- =============================================================

CREATE DATABASE IF NOT EXISTS registration_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE registration_db;

-- 1. 医生出诊排班 / 号源配置（门诊管理员维护；定时任务据此生成每日号源）
CREATE TABLE IF NOT EXISTS doctor_schedule (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  hospital_id   BIGINT       NOT NULL                COMMENT '医院ID（冗余，便于过滤）',
  department_id BIGINT       NOT NULL                COMMENT '科室ID（冗余）',
  doctor_user_id BIGINT      NOT NULL                COMMENT '医生用户ID（关联 sys_user）',
  schedule_date  DATE         NOT NULL                COMMENT '出诊日期',
  period         TINYINT      NOT NULL                COMMENT '时段 1=上午 2=下午',
  total_limit    INT          NOT NULL DEFAULT 0      COMMENT '号源总数',
  registered_count INT        NOT NULL DEFAULT 0      COMMENT '已挂号数（冗余，便于统计/余量计算）',
  status         TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1=启用 0=停用/作废',
  remark         VARCHAR(255) DEFAULT NULL,
  create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule (doctor_user_id, schedule_date, period),
  KEY idx_hospital (hospital_id),
  KEY idx_dept (department_id),
  KEY idx_date (schedule_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生出诊排班/号源配置';

-- 2. 号源池（按排班预生成，每个号位一条；status 仅 空闲/占用 两态，余量=空闲数）
CREATE TABLE IF NOT EXISTS number_source (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  hospital_id    BIGINT       NOT NULL,
  department_id  BIGINT       NOT NULL,
  doctor_user_id BIGINT       NOT NULL,
  schedule_id    BIGINT       NOT NULL                COMMENT '关联 doctor_schedule',
  schedule_date  DATE         NOT NULL,
  period         TINYINT      NOT NULL                COMMENT '1=上午 2=下午',
  seq_no         INT          NOT NULL                COMMENT '就诊序号（1..total_limit）',
  registration_id BIGINT      DEFAULT NULL            COMMENT '占用时关联的挂号记录ID（空闲为 NULL）',
  status         TINYINT      NOT NULL DEFAULT 1      COMMENT '1=空闲可约 2=已占用',
  create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_schedule (schedule_id),
  KEY idx_doctor_date (doctor_user_id, schedule_date, period),
  KEY idx_hospital (hospital_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号源池';

-- 3. 挂号 / 预约 / 取号记录（状态机核心载体）
CREATE TABLE IF NOT EXISTS registration (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  registration_no VARCHAR(32)  NOT NULL                COMMENT '挂号凭证号（业务单号，唯一）',
  hospital_id     BIGINT       NOT NULL,
  department_id   BIGINT       NOT NULL,
  doctor_user_id  BIGINT       NOT NULL,
  schedule_id     BIGINT       NOT NULL,
  number_source_id BIGINT      NOT NULL,
  schedule_date   DATE         NOT NULL,
  period          TINYINT      NOT NULL                COMMENT '1=上午 2=下午',
  seq_no          INT          NOT NULL                COMMENT '就诊序号（取自号源）',
  patient_name    VARCHAR(50)   NOT NULL                COMMENT '患者姓名',
  patient_phone   VARCHAR(20)   NOT NULL                COMMENT '患者手机号',
  patient_id_card VARCHAR(18)   DEFAULT NULL            COMMENT '患者身份证号（取号/查询用）',
  source_type     TINYINT       NOT NULL                COMMENT '来源 1=线上预约 2=现场窗口 3=自助机',
  pay_status      TINYINT       NOT NULL DEFAULT 0      COMMENT '0=未支付 1=已支付 2=超时作废',
  pay_amount      DECIMAL(10,2) DEFAULT 0.00           COMMENT '收费金额',
  pay_time        DATETIME      DEFAULT NULL,
  status          TINYINT       NOT NULL DEFAULT 1      COMMENT '状态机：1=已预约(锁定) 2=已取号 3=已就诊 4=已取消 5=已退号 6=过期失效',
  expire_time     DATETIME      DEFAULT NULL            COMMENT '预约锁定过期时间（支付/取号时限）',
  treat_time      DATETIME      DEFAULT NULL            COMMENT '就诊时间',
  refund_amount   DECIMAL(10,2) DEFAULT NULL           COMMENT '退款金额',
  refund_time     DATETIME      DEFAULT NULL            COMMENT '退款时间',
  remark          VARCHAR(255) DEFAULT NULL,
  create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_reg_no (registration_no),
  KEY idx_hospital (hospital_id),
  KEY idx_dept (department_id),
  KEY idx_doctor_date (doctor_user_id, schedule_date),
  KEY idx_phone (patient_phone),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号/预约/取号记录（状态机核心）';
