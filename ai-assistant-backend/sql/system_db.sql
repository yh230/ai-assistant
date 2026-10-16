-- ============================================================
-- 用户中心库（system-service）：RBAC 五表（含员工医院/科室绑定列）
-- 执行方式：mysql 直接执行本脚本即可（含建库）
-- 初始数据由 system-service 的 SystemDataSeeder 首次启动自动播种
-- ============================================================

CREATE DATABASE IF NOT EXISTS system_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE system_db;

CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(64)  NOT NULL COMMENT '登录账号',
  password      VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
  name          VARCHAR(64)  DEFAULT '' COMMENT '姓名',
  phone         VARCHAR(32)  DEFAULT NULL COMMENT '手机号（登录用，唯一；未绑定为 NULL）',
  email         VARCHAR(128) DEFAULT '' COMMENT '邮箱（非必填）',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：1-启用，0-禁用',
  hospital_id   BIGINT       DEFAULT NULL COMMENT '所属医院ID',
  department_id BIGINT       DEFAULT NULL COMMENT '所属科室ID（一人一科室）',
  create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  UNIQUE KEY uk_user_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工用户表';

CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码',
  role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称',
  role_desc   VARCHAR(255) DEFAULT '' COMMENT '角色描述',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_user_role (
  id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  role_id BIGINT NOT NULL COMMENT '角色ID',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_id),
  KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色中间表';

CREATE TABLE IF NOT EXISTS sys_menu (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID，顶级为0',
  menu_name   VARCHAR(64)  NOT NULL COMMENT '菜单名称',
  path        VARCHAR(128) DEFAULT '' COMMENT '路由 path',
  component   VARCHAR(128) DEFAULT '' COMMENT '组件路径',
  perms       VARCHAR(128) DEFAULT '' COMMENT '权限标识，如 system:user:add',
  type        TINYINT      NOT NULL DEFAULT 1 COMMENT '类型：1-菜单，2-按钮',
  sort        INT          NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限表';

CREATE TABLE IF NOT EXISTS sys_role_menu (
  id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  role_id BIGINT NOT NULL COMMENT '角色ID',
  menu_id BIGINT NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_menu (role_id, menu_id),
  KEY idx_menu_id (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联表';

-- ============================================================
-- 患者账号（V1.0：与员工 sys_user 物理隔离，独立登录名/密码体系）
-- ============================================================
CREATE TABLE IF NOT EXISTS patient_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    phone       VARCHAR(20)  NOT NULL COMMENT '手机号（登录名）',
    password    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 密文）',
    name        VARCHAR(50)  NOT NULL COMMENT '患者姓名',
    hospital_id BIGINT       NOT NULL COMMENT '绑定医院 ID',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=正常 0=禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_patient_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '患者账号';

CREATE TABLE IF NOT EXISTS sys_oper_log (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  module      VARCHAR(64)   NOT NULL COMMENT '业务模块',
  action      VARCHAR(64)   NOT NULL COMMENT '操作动作',
  user_id     BIGINT        DEFAULT NULL COMMENT '操作人ID（匿名为空）',
  username    VARCHAR(64)   DEFAULT '' COMMENT '操作人账号（匿名为空）',
  identity    VARCHAR(16)   DEFAULT '' COMMENT '身份：staff-员工，patient-患者（匿名为空）',
  method      VARCHAR(8)    DEFAULT '' COMMENT 'HTTP方法',
  request_uri VARCHAR(255)  DEFAULT '' COMMENT '请求路径',
  params      VARCHAR(2000) DEFAULT '' COMMENT '请求参数（敏感字段脱敏、超长截断）',
  ip          VARCHAR(64)   DEFAULT '' COMMENT '操作IP',
  status      TINYINT       NOT NULL DEFAULT 0 COMMENT '结果：0-成功，1-失败',
  error_msg   VARCHAR(500)  DEFAULT '' COMMENT '错误摘要（失败时）',
  cost_ms     BIGINT        DEFAULT NULL COMMENT '耗时毫秒',
  oper_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (id),
  KEY idx_oper_time (oper_time),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- ============================================================
-- 患者站内信（预约域 Kafka 事件消费落库，患者端拉取查看）
-- ============================================================
CREATE TABLE IF NOT EXISTS patient_notification (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  patient_id  BIGINT       NOT NULL COMMENT '患者用户ID',
  hospital_id BIGINT       DEFAULT NULL COMMENT '归属医院ID（管理端过滤用）',
  type        VARCHAR(32)  NOT NULL COMMENT '通知类型（对应事件类型码）',
  title       VARCHAR(100) NOT NULL COMMENT '标题',
  content     VARCHAR(500) NOT NULL COMMENT '内容',
  biz_no      VARCHAR(64)  DEFAULT '' COMMENT '关联业务号（挂号号等）',
  read_flag   TINYINT      NOT NULL DEFAULT 0 COMMENT '已读：0-未读，1-已读',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_patient_read (patient_id, read_flag),
  KEY idx_patient_time (patient_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者站内信';
