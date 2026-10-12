package com.aiassistant.common.constant;

/**
 * 安全相关常量
 *
 * <p>变更点（Sa-Token→Spring Security 迁移）：恢复认证请求头与令牌前缀两常量
 * （批 1 曾按"JWT 时代遗留"删除），供 Security 过滤器链读取请求头与 JWT 解析去除令牌前缀</p>
 *
 * @author yh
 * @date 2026-10-12
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    /** 默认重置密码 */
    public static final String DEFAULT_PASSWORD = "123456";

    // 变更点（Sa-Token→Spring Security 迁移）：以下两常量批 1 曾按"JWT 时代遗留"删除，
    // 本次因过滤器链读取请求头、JWT 解析去除令牌前缀而重新赋予用途（Task 2/3 使用）

    /** 认证请求头名称（Bearer 令牌载体） */
    public static final String AUTHORIZATION = "Authorization";

    /** 令牌前缀，与前端 `Authorization: Bearer <token>` 写法保持一致 */
    public static final String TOKEN_PREFIX = "Bearer ";
}
