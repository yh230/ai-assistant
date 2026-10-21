package com.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.auth.McpAuthFilter;
import com.mcp.auth.McpKeyHasher;
import com.mcp.ratelimit.McpRateLimiter;
import com.mcp.support.McpMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * MCP 认证过滤器注册
 *
 * @author yh
 * @date 2026-10-21
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpAuthConfig {

    private final McpProperties properties;
    private final McpKeyHasher keyHasher;
    private final McpRateLimiter rateLimiter;
    private final McpMetrics metrics;
    private final ObjectMapper objectMapper;

    /**
     * 启动时检查是否存在可用 Key
     */
    @PostConstruct
    void warnWhenNoUsableKey() {
        if (!McpAuthFilter.hasUsableKey(properties)) {
            log.error("MCP 未配置可用的 API Key，所有请求将被拒绝；请在 Nacos mcp-shared.yaml 中配置 key-sha256 与 hospital-ids");
        }
    }

    /**
     * 注册只作用于 MCP 端点的认证过滤器
     *
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilter() {
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(
                new McpAuthFilter(properties, keyHasher, rateLimiter, metrics, objectMapper));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
