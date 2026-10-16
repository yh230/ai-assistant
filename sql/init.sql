-- =============================================================
-- AI 医院助手 · 一键初始化（四库建库 + 全部建表）
-- 执行：mysql -uroot -p < sql/init.sql
-- 可重复执行（CREATE DATABASE/TABLE IF NOT EXISTS）；全新环境执行一次即建好全部表。
-- 角色/菜单/演示数据由各服务首次启动时的 Seeder 播种，本脚本只建结构。
-- =============================================================

-- =============================================================
-- 一、用户中心库（system-service）：员工 RBAC 五表 + 患者账号 + 操作日志 + 患者站内信
-- =============================================================
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者账号';

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

-- =============================================================
-- 二、医院库（hospital-service）：医院 / 科室 / 出勤
-- =============================================================
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

-- =============================================================
-- 三、挂号库（registration-service）：排班 / 号源池 / 挂号记录
-- 依赖：按 ID 引用 system_db.sys_user 与 hospital_db.department（无外键）
-- =============================================================
CREATE DATABASE IF NOT EXISTS registration_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE registration_db;

CREATE TABLE IF NOT EXISTS doctor_schedule (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  hospital_id      BIGINT       NOT NULL COMMENT '医院ID（冗余，便于过滤）',
  department_id    BIGINT       NOT NULL COMMENT '科室ID（冗余）',
  doctor_user_id   BIGINT       NOT NULL COMMENT '医生用户ID（关联 sys_user）',
  schedule_date    DATE         NOT NULL COMMENT '出诊日期',
  period           TINYINT      NOT NULL COMMENT '时段 1=上午 2=下午',
  total_limit      INT          NOT NULL DEFAULT 0 COMMENT '号源总数',
  registered_count INT          NOT NULL DEFAULT 0 COMMENT '已挂号数（冗余，便于统计/余量计算）',
  status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1=启用 0=停用/作废',
  remark           VARCHAR(255) DEFAULT NULL COMMENT '备注',
  create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule (doctor_user_id, schedule_date, period),
  KEY idx_hospital (hospital_id),
  KEY idx_dept (department_id),
  KEY idx_date (schedule_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生出诊排班/号源配置';

CREATE TABLE IF NOT EXISTS number_source (
  id              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  hospital_id     BIGINT   NOT NULL COMMENT '医院ID',
  department_id   BIGINT   NOT NULL COMMENT '科室ID',
  doctor_user_id  BIGINT   NOT NULL COMMENT '医生用户ID',
  schedule_id     BIGINT   NOT NULL COMMENT '关联 doctor_schedule',
  schedule_date   DATE     NOT NULL COMMENT '出诊日期',
  period          TINYINT  NOT NULL COMMENT '1=上午 2=下午',
  seq_no          INT      NOT NULL COMMENT '就诊序号（1..total_limit）',
  registration_id BIGINT   DEFAULT NULL COMMENT '占用时关联的挂号记录ID（空闲为 NULL）',
  status          TINYINT  NOT NULL DEFAULT 1 COMMENT '1=空闲可约 2=已占用',
  create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_schedule (schedule_id),
  KEY idx_doctor_date (doctor_user_id, schedule_date, period),
  KEY idx_hospital (hospital_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号源池';

CREATE TABLE IF NOT EXISTS registration (
  id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  registration_no  VARCHAR(32)   NOT NULL COMMENT '挂号凭证号（业务单号，唯一）',
  hospital_id      BIGINT        NOT NULL COMMENT '医院ID',
  department_id    BIGINT        NOT NULL COMMENT '科室ID',
  doctor_user_id   BIGINT        NOT NULL COMMENT '医生用户ID',
  schedule_id      BIGINT        NOT NULL COMMENT '排班ID',
  number_source_id BIGINT        NOT NULL COMMENT '号源ID',
  schedule_date    DATE          NOT NULL COMMENT '就诊日期',
  period           TINYINT       NOT NULL COMMENT '1=上午 2=下午',
  seq_no           INT           NOT NULL COMMENT '就诊序号（取自号源）',
  patient_name     VARCHAR(50)   NOT NULL COMMENT '患者姓名',
  patient_phone    VARCHAR(20)   NOT NULL COMMENT '患者手机号',
  patient_id_card  VARCHAR(18)   DEFAULT NULL COMMENT '患者身份证号（取号/查询用）',
  patient_id       BIGINT        DEFAULT NULL COMMENT '患者账号ID（AI挂号；匿名预约为空）',
  source_type      TINYINT       NOT NULL COMMENT '来源 1=线上预约 2=现场窗口 3=自助机',
  pay_status       TINYINT       NOT NULL DEFAULT 0 COMMENT '0=未支付 1=已支付 2=超时作废',
  pay_amount       DECIMAL(10,2) DEFAULT 0.00 COMMENT '收费金额',
  pay_time         DATETIME      DEFAULT NULL COMMENT '支付时间',
  status           TINYINT       NOT NULL DEFAULT 1 COMMENT '状态机：1=已预约(锁定) 2=已取号 3=已就诊 4=已取消 5=已退号 6=过期失效',
  expire_time      DATETIME      DEFAULT NULL COMMENT '预约锁定过期时间（支付/取号时限）',
  treat_time       DATETIME      DEFAULT NULL COMMENT '就诊时间',
  refund_amount    DECIMAL(10,2) DEFAULT NULL COMMENT '退款金额',
  refund_time      DATETIME      DEFAULT NULL COMMENT '退款时间',
  remark           VARCHAR(255)  DEFAULT NULL COMMENT '备注',
  create_time      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_reg_no (registration_no),
  KEY idx_hospital (hospital_id),
  KEY idx_dept (department_id),
  KEY idx_doctor_date (doctor_user_id, schedule_date),
  KEY idx_phone (patient_phone),
  KEY idx_status (status),
  KEY idx_registration_patient (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号/预约/取号记录（状态机核心）';

-- =============================================================
-- 四、AI 聊天库（ai-chat-service）：角色 / 会话 / 消息 / 知识库 / 待确认操作
-- 说明：本库表由 JPA 管理，应用 ddl-auto=update 亦会创建；此处显式建表以支持"仅初始化即可建齐"。
-- =============================================================
CREATE DATABASE IF NOT EXISTS chat_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE chat_db;

CREATE TABLE IF NOT EXISTS ai_role (
  id            VARCHAR(32)  NOT NULL COMMENT '角色ID（角色标识）',
  name          VARCHAR(50)  NOT NULL COMMENT '角色显示名称',
  avatar        VARCHAR(16)  DEFAULT NULL COMMENT '角色图标（emoji）',
  description   VARCHAR(255) DEFAULT NULL COMMENT '角色简介',
  system_prompt TEXT         NOT NULL COMMENT '系统提示词（人设与行为规范）',
  sort_order    INT          DEFAULT NULL COMMENT '展示排序（越小越靠前）',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：1-启用，0-禁用',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手角色';

CREATE TABLE IF NOT EXISTS chat_session (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  session_id   VARCHAR(64)  NOT NULL COMMENT '会话标识',
  title        VARCHAR(200) NOT NULL COMMENT '会话标题',
  user_id      BIGINT       DEFAULT NULL COMMENT '归属用户ID（员工=sys_user.id，患者=patient_user.id）',
  identity     VARCHAR(10)  DEFAULT NULL COMMENT '归属身份：staff/patient',
  session_type VARCHAR(10)  DEFAULT NULL COMMENT '会话类型：CHAT=闲聊/TRIAGE=导诊/RAG=知识库',
  hospital_id  BIGINT       DEFAULT NULL COMMENT '归属医院ID（管理端数据权限过滤）',
  created_at   DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at   DATETIME     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 聊天会话';

CREATE TABLE IF NOT EXISTS chat_message (
  id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  session_id     VARCHAR(64) NOT NULL COMMENT '会话标识',
  role           VARCHAR(20) NOT NULL COMMENT '角色：user/assistant',
  content        TEXT        NOT NULL COMMENT '消息内容',
  reference_json TEXT        DEFAULT NULL COMMENT '引用来源 JSON（RAG 回答的知识库来源；无来源为 NULL）',
  created_at     DATETIME    DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_chat_message_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 聊天消息';

CREATE TABLE IF NOT EXISTS knowledge_document (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  hospital_id BIGINT       NOT NULL COMMENT '归属医院ID（数据隔离边界）',
  name        VARCHAR(200) NOT NULL COMMENT '文件名',
  type        VARCHAR(10)  DEFAULT NULL COMMENT '文件类型（md/txt）',
  size        BIGINT       DEFAULT NULL COMMENT '文件大小（字节）',
  chunk_count INT          DEFAULT NULL COMMENT '切片数量',
  status      INT          NOT NULL DEFAULT 0 COMMENT '处理状态：0-处理中（待切片向量化），1-就绪',
  content     LONGTEXT     DEFAULT NULL COMMENT '文档全文（删除重建向量用）',
  create_by   BIGINT       DEFAULT NULL COMMENT '上传人（员工用户ID）',
  create_time DATETIME     DEFAULT NULL COMMENT '上传时间',
  PRIMARY KEY (id),
  KEY idx_knowledge_hospital (hospital_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档';

CREATE TABLE IF NOT EXISTS pending_action (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  action_id    VARCHAR(64)  NOT NULL COMMENT '确认ID（前端回传的业务标识）',
  session_id   VARCHAR(64)  NOT NULL COMMENT '归属会话',
  user_id      BIGINT       NOT NULL COMMENT '归属用户ID',
  identity     VARCHAR(10)  NOT NULL COMMENT '归属身份（patient）',
  tool_name    VARCHAR(50)  NOT NULL COMMENT '工具名称（bookAppointment/cancelAppointment）',
  tool_args    TEXT         DEFAULT NULL COMMENT '工具参数（JSON）',
  summary_text VARCHAR(500) DEFAULT NULL COMMENT '确认弹窗展示摘要',
  status       VARCHAR(12)  NOT NULL COMMENT '状态：PENDING/CONFIRMED/CANCELLED/EXPIRED',
  create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
  expire_time  DATETIME     DEFAULT NULL COMMENT '过期时间',
  confirm_time DATETIME     DEFAULT NULL COMMENT '确认时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_action_id (action_id),
  KEY idx_pending_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待确认操作（写工具二次确认状态机载体）';
