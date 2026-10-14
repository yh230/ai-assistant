package com.gateway.config;

import com.common.jwt.JwtTokenService;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * AI 接口限流键解析：优先按登录用户（JWT userId），匿名回退远端 IP
 *
 * @author yh
 * @date 2026-10-13
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver(JwtTokenService jwtTokenService) {
        return exchange -> {
            String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                try {
                    return Mono.just(String.valueOf(
                            jwtTokenService.parse(authorization.substring(7)).userId()));
                } catch (Exception ignored) {
                    // 解析失败回退 IP
                }
            }
            return Mono.just(exchange.getRequest().getRemoteAddress() == null
                    ? "anonymous"
                    : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
        };
    }
}
