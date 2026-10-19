package com.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 跨域配置 - 仅放行白名单内的前端源
 * <p>
 * 来源列表由 {@code security.cors.allowed-origins}（逗号分隔）提供，禁止使用通配符；
 * 生产环境需在 Nacos 覆盖为真实域名（启动守卫会拒绝 localhost/通配符）。
 *
 * @author yh
 * @date 2026-10-19
 */
@Configuration
public class CorsConfig {

    /**
     * 构建跨域过滤器，统一在网关出口注入 CORS 响应头
     *
     * @param allowedOrigins 逗号分隔的允许来源
     * @return 响应式跨域过滤器
     */
    @Bean
    public CorsWebFilter corsWebFilter(
            @Value("${security.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
            String allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfig(allowedOrigins));
        return new CorsWebFilter(source);
    }

    /**
     * 由逗号分隔的来源串构建跨域配置（包内可见，便于单元测试）
     *
     * @param allowedOrigins 逗号分隔的允许来源
     * @return 跨域配置
     */
    static CorsConfiguration buildConfig(String allowedOrigins) {
        if (allowedOrigins == null || !StringUtils.hasText(allowedOrigins)) {
            throw new IllegalStateException(
                    "security.cors.allowed-origins must not be null or blank");
        }
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "security.cors.allowed-origins must contain at least one non-blank origin");
        }
        if (origins.contains("*")) {
            throw new IllegalStateException(
                    "security.cors.allowed-origins must not contain wildcard '*'");
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(origins);
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setMaxAge(3600L);
        return config;
    }
}
