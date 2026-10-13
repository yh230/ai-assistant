-- ============================================================
-- RBAC 标准五张表 DDL（MySQL 8.0）
-- 执行方式：在 ai_assistant 库中执行本脚本创建权限相关表
-- 注意：本脚本只建表，初始数据由后端 SystemDataSeeder 自动播种
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username    VARCHAR(64)  NOT NULL COMMENT '登录账号',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
  name        VARCHAR(64)  DEFAULT '' COMMENT '姓名',
  phone       VARCHAR(32)  DEFAULT '' COMMENT '手机号',
  email       VARCHAR(128) DEFAULT '' COMMENT '邮箱（非必填）',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：1-启用，0-禁用',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
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
