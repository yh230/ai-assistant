package com.mcp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.config.McpProperties;
import com.mcp.ratelimit.McpRateLimiter;
import com.mcp.support.McpMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * MCP API Key 认证过滤器
 *
 * @author yh
 * @date 2026-10-21
 */
@Slf4j
@RequiredArgsConstructor
public class McpAuthFilter extends OncePerRequestFilter {

    /** 认证请求头 */
    public static final String API_KEY_HEADER = "X-API-Key";

    /** 请求过多状态码（Servlet 6.0 无 SC_TOO_MANY_REQUESTS 常量） */
    private static final int SC_TOO_MANY_REQUESTS = 429;

    /** Key 哈希格式（SHA-256 十六进制） */
    private static final Pattern KEY_HASH_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    private final McpProperties properties;
    private final McpKeyHasher keyHasher;
    private final McpRateLimiter rateLimiter;
    private final McpMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!hasUsableKey(properties)) {
            metrics.authFailure("empty_config");
            log.error("MCP 认证失败: reason=empty_config, remote={}", request.getRemoteAddr());
            writeUnauthorized(response, "invalid_api_key");
            return;
        }
        String raw = request.getHeader(API_KEY_HEADER);
        if (raw == null || raw.isBlank()) {
            metrics.authFailure("missing_key");
            log.warn("MCP 认证失败: reason=missing_key, remote={}", request.getRemoteAddr());
            writeUnauthorized(response, "invalid_api_key");
            return;
        }
        McpProperties.Key matched = findKey(raw);
        if (matched == null) {
            metrics.authFailure("invalid_key");
            log.warn("MCP 认证失败: reason=invalid_key, remote={}", request.getRemoteAddr());
            writeUnauthorized(response, "invalid_api_key");
            return;
        }
        Integer configuredLimit = matched.getRateLimitPerMinute();
        int effectiveLimit = (configuredLimit == null || configuredLimit < 1) ? 60 : configuredLimit;
        if (!rateLimiter.tryAcquire(matched.getConsumer(), effectiveLimit)) {
            metrics.rateLimited(matched.getConsumer());
            log.warn("MCP 限流命中: consumer={}, remote={}", matched.getConsumer(), request.getRemoteAddr());
            response.setHeader("Retry-After", "60");
            writeJson(response, SC_TOO_MANY_REQUESTS, "rate_limit_exceeded");
            return;
        }
        McpPrincipalContext.attach(request, new McpPrincipal(
                matched.getConsumer(), List.copyOf(matched.getHospitalIds()), effectiveLimit));
        filterChain.doFilter(request, response);
    }

    /**
     * 判断配置中是否存在可用 Key
     *
     * @param properties MCP 配置
     * @return 是否存在启用的可用 Key
     */
    public static boolean hasUsableKey(McpProperties properties) {
        if (properties == null || properties.getAuth() == null || properties.getAuth().getKeys() == null) {
            return false;
        }
        for (McpProperties.Key key : properties.getAuth().getKeys()) {
            if (isUsableKey(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断单个 Key 配置是否可用
     *
     * @param key Key 配置
     * @return 是否可用
     */
    public static boolean isUsableKey(McpProperties.Key key) {
        return key != null
                && key.isEnabled()
                && key.getConsumer() != null
                && !key.getConsumer().isBlank()
                && key.getKeySha256() != null
                && KEY_HASH_PATTERN.matcher(key.getKeySha256().trim()).matches()
                && key.getHospitalIds() != null
                && !key.getHospitalIds().isEmpty()
                && key.getHospitalIds().stream().allMatch(Objects::nonNull);
    }

    /**
     * 在配置中查找匹配的 Key
     *
     * @param raw 原始 Key
     * @return 匹配的配置，未匹配时为 null
     */
    private McpProperties.Key findKey(String raw) {
        if (properties.getAuth() == null || properties.getAuth().getKeys() == null) {
            return null;
        }
        for (McpProperties.Key key : properties.getAuth().getKeys()) {
            if (!isUsableKey(key)) {
                continue;
            }
            if (keyHasher.matches(raw, key.getKeySha256())) {
                return key;
            }
        }
        return null;
    }

    /**
     * 输出未认证响应
     *
     * @param response 响应
     * @param error 错误码
     * @throws IOException 写出失败
     */
    private void writeUnauthorized(HttpServletResponse response, String error) throws IOException {
        response.setHeader("WWW-Authenticate", "ApiKey");
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, error);
    }

    /**
     * 输出 HTTP 层错误
     *
     * @param response 响应
     * @param status HTTP 状态码
     * @param error 错误码
     * @throws IOException 写出失败
     */
    private void writeJson(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("error", error));
    }
}
