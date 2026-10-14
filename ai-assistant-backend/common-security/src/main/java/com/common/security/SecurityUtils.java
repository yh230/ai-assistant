package com.common.security;

import com.common.auth.UserSnapshot;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录人上下文工具（从 SecurityContext 读取，认证信息由 JwtAuthenticationFilter 写入）
 *
 * @author yh
 * @date 2026-10-12
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 获取当前登录用户 ID，未登录抛异常 */
    public static Long getCurrentUserId() {
        Long userId = getCurrentUserIdOrNull();
        if (userId == null) {
            throw new AuthenticationCredentialsNotFoundException("未登录");
        }
        return userId;
    }

    /** 获取当前登录用户 ID，未登录返回 null */
    public static Long getCurrentUserIdOrNull() {
        UserSnapshot snapshot = currentSnapshot();
        return snapshot == null ? null : snapshot.getUserId();
    }

    /** 获取当前登录用户快照，未登录返回 null */
    public static UserSnapshot currentSnapshot() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getDetails() == null) {
            return null;
        }
        Object details = authentication.getDetails();
        return details instanceof UserSnapshot snapshot ? snapshot : null;
    }
}
