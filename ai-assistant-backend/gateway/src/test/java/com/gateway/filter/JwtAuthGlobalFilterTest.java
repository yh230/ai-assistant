package com.gateway.filter;

import com.common.jwt.JwtTokenService;
import com.common.jwt.TokenRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关鉴权过滤器白名单匹配与路径穿越防御测试
 *
 * @author yh
 * @date 2026-11-05
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthGlobalFilterTest {

    @Mock
    private JwtTokenService jwtTokenService;
    @Mock
    private TokenRevocationService tokenRevocationService;

    private JwtAuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthGlobalFilter(jwtTokenService, tokenRevocationService);
        ReflectionTestUtils.setField(filter, "whitelist", List.of(
                "/api/login",
                "/api/registration/appointments",
                "/api/registration/appointments/query",
                "/api/registration/appointments/*/confirm-pay",
                "/api/registration/appointments/*/cancel"));
    }

    @Test
    void whitelistsExactAndSingleSegmentPatterns() {
        assertTrue(passes("/api/registration/appointments"));
        assertTrue(passes("/api/registration/appointments/query"));
        assertTrue(passes("/api/registration/appointments/9/cancel"));
        assertTrue(passes("/api/registration/appointments/9/confirm-pay"));
    }

    @Test
    void rejectsPathOutsideWhitelist() {
        MockServerWebExchange exchange = exchange("/api/registration/appointmentsX");
        assertFalse(passes(exchange));
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsMultiSegmentPathNotCoveredBySingleStar() {
        MockServerWebExchange exchange = exchange("/api/registration/appointments/9/nested/cancel");
        assertFalse(passes(exchange));
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsDirectoryTraversal() {
        MockServerWebExchange exchange = exchange("/api/registration/appointments/../inner/auth/kickout");
        assertFalse(passes(exchange));
        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
    }

    @Test
    void blocksInnerEndpointsFromOutside() {
        MockServerWebExchange exchange = exchange("/inner/auth/kickout");
        assertFalse(passes(exchange));
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    private boolean passes(String path) {
        return passes(exchange(path));
    }

    private boolean passes(MockServerWebExchange exchange) {
        AtomicBoolean called = new AtomicBoolean(false);
        filter.filter(exchange, e -> {
            called.set(true);
            return Mono.empty();
        }).block();
        return called.get();
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
