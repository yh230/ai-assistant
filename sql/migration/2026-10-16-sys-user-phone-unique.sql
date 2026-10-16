-- 员工手机号唯一化（手机号验证码登录前置）：空串置 NULL（唯一索引允许多个 NULL）
UPDATE sys_user SET phone = NULL WHERE phone = '';
ALTER TABLE sys_user ADD UNIQUE KEY uk_user_phone (phone);
