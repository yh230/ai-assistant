package com.gateway.config;

import com.common.jwt.JwtTokenService;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 限流键解析：AI 路由按登录用户（JWT userId）匿名回退 IP；登录与公开接口直接按 IP
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

    /**
     * 按远端 IP 解析限流键（登录、注册、匿名预约等无 JWT 场景）
     *
     * @return IP 键解析器（取不到远端地址时回退 anonymous）
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(exchange.getRequest().getRemoteAddress() == null
                ? "anonymous"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
    }
}
