-- ============================================================
-- 医院库（hospital-service）：医院科室人员 & 出勤数据权限模块 DDL（MySQL 8.0）
-- 执行方式：mysql 直接执行本脚本即可（含建库）
-- 说明：本脚本只建表，角色与菜单由后端 SystemDataSeeder 自动播种
-- ============================================================

CREATE DATABASE IF NOT EXISTS hospital_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE hospital_db;

CREATE TABLE IF NOT EXISTS hospital (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name        VARCHAR(128) NOT NULL COMMENT '医院名称',
  address     VARCHAR(255) DEFAULT '' COMMENT '地址',
  phone       VARCHAR(32)  DEFAULT '' COMMENT '联系电话',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_hospital_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医院表';

CREATE TABLE IF NOT EXISTS department (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  hospital_id      BIGINT       NOT NULL COMMENT '所属医院ID',
  name             VARCHAR(128) NOT NULL COMMENT '科室名称',
  director_user_id BIGINT       DEFAULT NULL COMMENT '科室主任用户ID（sys_user.id）',
  status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
  create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_hospital_id (hospital_id),
  KEY idx_director (director_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科室表';

CREATE TABLE IF NOT EXISTS attendance (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id       BIGINT       NOT NULL COMMENT '员工ID（sys_user.id）',
  hospital_id   BIGINT       NOT NULL COMMENT '医院ID（冗余存储，便于统计/过滤）',
  department_id BIGINT       DEFAULT NULL COMMENT '科室ID（冗余存储，便于按科室过滤；院长/院级人员不归属科室时为 NULL）',
  attend_date   DATE         NOT NULL COMMENT '出勤日期',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '出勤状态：1-出勤 2-缺勤 3-请假 4-迟到',
  check_in      VARCHAR(8)   DEFAULT '' COMMENT '上班打卡 HH:mm',
  check_out     VARCHAR(8)   DEFAULT '' COMMENT '下班打卡 HH:mm',
  remark        VARCHAR(255) DEFAULT '' COMMENT '备注',
  create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_date (user_id, attend_date),
  KEY idx_dept_date (department_id, attend_date),
  KEY idx_hospital_date (hospital_id, attend_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出勤表';
