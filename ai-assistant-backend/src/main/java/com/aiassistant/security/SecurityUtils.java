package com.aiassistant.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录人上下文助手
 * <p>
 * 变更点（Sa-Token→Spring Security 迁移）：替代全部 {@code StpUtil} 静态调用
 * （isLogin/getLoginIdAsLong），语义与调用点原行为一一对应
 *
 * @author yh
 * @date 2026-10-12
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户 ID
     *
     * @return 用户 ID
     * @throws AuthenticationCredentialsNotFoundException 未登录（由全局异常处理器包装为 401）
     */
    public static Long getCurrentUserId() {
        Long userId = getCurrentUserIdOrNull();
        if (userId == null) {
            throw new AuthenticationCredentialsNotFoundException("未登录");
        }
        return userId;
    }

    /**
     * 获取当前登录用户 ID（未登录返回 null，供"未登录→降级"场景使用）
     *
     * @return 用户 ID 或 null
     */
    public static Long getCurrentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof DbUserDetailsService.LoginUser loginUser) {
            return loginUser.userId();
        }
        return null;
    }

    /**
     * 当前请求是否已认证
     *
     * @return true=已登录
     */
    public static boolean isAuthenticated() {
        return getCurrentUserIdOrNull() != null;
    }
}
