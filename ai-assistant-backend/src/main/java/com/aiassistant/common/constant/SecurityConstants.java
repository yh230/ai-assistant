package com.aiassistant.common.constant;

/**
 * 安全相关常量
 *
 * <p>认证请求头与令牌前缀两常量，供 Security 过滤器链读取请求头与 JWT 解析去除令牌前缀</p>
 *
 * @author yh
 * @date 2026-10-12
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    /** 默认重置密码 */
    public static final String DEFAULT_PASSWORD = "123456";

    // 以下两常量用于过滤器链读取请求头、JWT 解析去除令牌前缀

    /** 认证请求头名称（Bearer 令牌载体） */
    public static final String AUTHORIZATION = "Authorization";

    /** 令牌前缀，与前端 `Authorization: Bearer <token>` 写法保持一致 */
    public static final String TOKEN_PREFIX = "Bearer ";
}
