-- 员工手机号唯一化（手机号验证码登录前置）：空串置 NULL（唯一索引允许多个 NULL）
UPDATE sys_user SET phone = NULL WHERE phone = '';
ALTER TABLE sys_user ADD UNIQUE KEY uk_user_phone (phone);
-- 列默认值置 NULL：存量库 phone 默认值为 ''，未填手机号的插入会落空串并占用唯一索引
ALTER TABLE sys_user MODIFY phone VARCHAR(32) DEFAULT NULL COMMENT '手机号（登录用，唯一；未绑定为 NULL）';
