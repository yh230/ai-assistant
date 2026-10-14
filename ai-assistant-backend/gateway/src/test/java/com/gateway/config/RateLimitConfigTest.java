package com.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 网关限流键解析测试
 *
 * @author yh
 * @date 2026-10-14
 */
class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void ipKeyResolverShouldUseRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/login")
                        .remoteAddress(new InetSocketAddress("10.1.2.3", 5000))
                        .build());
        assertEquals("10.1.2.3", config.ipKeyResolver().resolve(exchange).block());
    }

    @Test
    void ipKeyResolverShouldFallbackToAnonymousWhenNoRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/login").build());
        assertEquals("anonymous", config.ipKeyResolver().resolve(exchange).block());
    }
}
