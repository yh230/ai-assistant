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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MCP API Key 认证过滤器
 *
 * @author yh
 * @date 2026-10-21
 */
@RequiredArgsConstructor
public class McpAuthFilter extends OncePerRequestFilter {

    /** 认证请求头 */
    public static final String API_KEY_HEADER = "X-API-Key";

    private final McpProperties properties;
    private final McpKeyHasher keyHasher;
    private final McpRateLimiter rateLimiter;
    private final McpMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        McpProperties.Key matched = findKey(request.getHeader(API_KEY_HEADER));
        if (matched == null) {
            metrics.authFailure("invalid_key");
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_api_key");
            return;
        }
        Integer configuredLimit = matched.getRateLimitPerMinute();
        int effectiveLimit = (configuredLimit == null || configuredLimit < 1) ? 60 : configuredLimit;
        if (!rateLimiter.tryAcquire(matched.getConsumer(), effectiveLimit)) {
            metrics.rateLimited(matched.getConsumer());
            writeJson(response, HttpServletResponse.SC_TOO_MANY_REQUESTS, "rate_limit_exceeded");
            return;
        }
        McpPrincipalContext.set(new McpPrincipal(
                matched.getConsumer(), List.copyOf(matched.getHospitalIds()), effectiveLimit));
        try {
            filterChain.doFilter(request, response);
        } finally {
            McpPrincipalContext.clear();
        }
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
            if (key == null || !key.isEnabled() || key.getConsumer() == null || key.getConsumer().isBlank()
                    || key.getHospitalIds() == null || key.getHospitalIds().isEmpty()) {
                continue;
            }
            if (keyHasher.matches(raw, key.getKeySha256())) {
                return key;
            }
        }
        return null;
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
