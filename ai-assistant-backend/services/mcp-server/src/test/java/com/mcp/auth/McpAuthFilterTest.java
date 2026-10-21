package com.mcp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.config.McpProperties;
import com.mcp.ratelimit.McpRateLimiter;
import com.mcp.support.McpMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP API Key 认证过滤器测试
 *
 * @author yh
 * @date 2026-10-21
 */
class McpAuthFilterTest {

    private static final String RAW_KEY = "mcp_test_key";

    private final McpKeyHasher hasher = new McpKeyHasher();
    private McpProperties properties;
    private McpRateLimiter rateLimiter;
    private McpAuthFilter filter;

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        McpProperties.Key key = new McpProperties.Key();
        key.setConsumer("demo");
        key.setKeySha256(hasher.sha256(RAW_KEY));
        key.setHospitalIds(List.of(1L));
        key.setRateLimitPerMinute(60);
        key.setEnabled(true);
        properties.getAuth().setKeys(List.of(key));

        rateLimiter = mock(McpRateLimiter.class);
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        filter = new McpAuthFilter(properties, hasher, rateLimiter, mock(McpMetrics.class), new ObjectMapper());
    }

    @Test
    void rejectsMissingKey() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean(false);

        filter.doFilter(new MockHttpServletRequest("GET", "/mcp/sse"), response,
                (request, res) -> called.set(true));

        assertEquals(401, response.getStatus());
        assertEquals("ApiKey", response.getHeader("WWW-Authenticate"));
        assertFalse(called.get());
    }

    @Test
    void rejectsBlankKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/message");
        request.addHeader("X-API-Key", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertEquals(401, response.getStatus());
        assertEquals("ApiKey", response.getHeader("WWW-Authenticate"));
    }

    @Test
    void rejectsUnknownKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/message");
        request.addHeader("X-API-Key", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsDisabledKey() throws Exception {
        properties.getAuth().getKeys().get(0).setEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/message");
        request.addHeader("X-API-Key", RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsWhenNoKeysConfigured() throws Exception {
        properties.getAuth().setKeys(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/message");
        request.addHeader("X-API-Key", RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertEquals(401, response.getStatus());
    }

    @Test
    void authenticatesAndAttachesPrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/message");
        request.addHeader("X-API-Key", RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<McpPrincipal> captured = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> captured.set(
                (McpPrincipal) req.getAttribute(McpPrincipalContext.REQUEST_ATTRIBUTE)));

        assertNotNull(captured.get());
        assertEquals("demo", captured.get().consumer());
        assertTrue(captured.get().canAccess(1L));
        assertThrows(UnsupportedOperationException.class, () -> captured.get().hospitalIds().add(2L));
        assertEquals(captured.get(), request.getAttribute(McpPrincipalContext.REQUEST_ATTRIBUTE));
    }

    @Test
    void appliesDefaultLimitWhenRateLimitUnset() throws Exception {
        properties.getAuth().getKeys().get(0).setRateLimitPerMinute(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/message");
        request.addHeader("X-API-Key", RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<McpPrincipal> captured = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> captured.set(
                (McpPrincipal) req.getAttribute(McpPrincipalContext.REQUEST_ATTRIBUTE)));

        verify(rateLimiter).tryAcquire("demo", 60);
        assertEquals(60, captured.get().rateLimitPerMinute());
    }

    @Test
    void returnsTooManyRequestsWhenRateLimited() throws Exception {
        when(rateLimiter.tryAcquire("demo", 60)).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/message");
        request.addHeader("X-API-Key", RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
    }

    @Test
    void hasherMatchesNormalizedHex() {
        assertTrue(hasher.matches(RAW_KEY, hasher.sha256(RAW_KEY).toUpperCase()));
        assertFalse(hasher.matches("wrong", hasher.sha256(RAW_KEY)));
    }

    @Test
    void rejectsKeyWithInvalidHashFormat() {
        properties.getAuth().getKeys().get(0).setKeySha256("not-a-sha256");

        assertFalse(McpAuthFilter.hasUsableKey(properties));
        assertFalse(McpAuthFilter.isUsableKey(properties.getAuth().getKeys().get(0)));
    }

    @Test
    void rejectsKeyWithNullHospitalId() {
        properties.getAuth().getKeys().get(0).setHospitalIds(java.util.Arrays.asList(1L, null));

        assertFalse(McpAuthFilter.hasUsableKey(properties));
    }

    @Test
    void rejectsKeyWithBlankConsumer() {
        properties.getAuth().getKeys().get(0).setConsumer("  ");

        assertFalse(McpAuthFilter.hasUsableKey(properties));
    }
}
