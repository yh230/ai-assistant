package com.system.support;

import com.common.constant.SecurityConstants;
import com.system.entity.SysUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 密码处理支撑（默认密码回退 + 加密的统一入口）
 *
 * @author yh
 * @date 2026-10-10
 */
public final class PasswordSupport {

    private PasswordSupport() {
    }

    /**
     * 明文为空时回退默认密码，随后加密
     *
     * @param rawPassword 明文密码（可空）
     * @param passwordEncoder 密码加密器
     * @return 加密后的密码
     */
    public static String encodeOrDefault(String rawPassword, PasswordEncoder passwordEncoder) {
        String raw = StringUtils.hasText(rawPassword) ? rawPassword : SecurityConstants.DEFAULT_PASSWORD;
        return passwordEncoder.encode(raw);
    }

    /**
     * 重置用户密码（为空用默认密码）并刷新更新时间，由调用方执行 updateById
     *
     * @param user 用户实体
     * @param rawPassword 明文密码（可空）
     * @param passwordEncoder 密码加密器
     */
    public static void resetPassword(SysUser user, String rawPassword, PasswordEncoder passwordEncoder) {
        user.setPassword(encodeOrDefault(rawPassword, passwordEncoder));
        user.setUpdateTime(LocalDateTime.now());
    }
}
