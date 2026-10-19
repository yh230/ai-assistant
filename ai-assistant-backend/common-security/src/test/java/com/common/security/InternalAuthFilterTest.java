package com.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部调用认证过滤器测试：令牌校验与常量时间比较的边界行为
 * <p>
 * 过滤器按 {@code servletPath} 判定 {@code /inner/} 前缀，故 Mock 请求除 requestURI 外
 * 必须显式设置 servletPath，否则过滤器会跳过校验导致用例假通过。
 *
 * @author yh
 * @date 2026-10-19
 */
class InternalAuthFilterTest {

    private static final String TOKEN = "expected-token-0123456789abcdef";

    private final InternalAuthFilter filter = new InternalAuthFilter(TOKEN, new ObjectMapper());

    @Test
    void rejectsWrongToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/inner/user/1");
        // 过滤器按 servletPath 判定内部接口，测试需与 requestURI 保持一致
        request.setServletPath("/inner/user/1");
        request.addHeader("X-Internal-Token", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void passesCorrectToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/inner/user/1");
        request.setServletPath("/inner/user/1");
        request.addHeader("X-Internal-Token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsMissingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/inner/user/1");
        request.setServletPath("/inner/user/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void rejectsDifferentLengthToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/inner/user/1");
        request.setServletPath("/inner/user/1");
        request.addHeader("X-Internal-Token", TOKEN + "-suffix");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void rejectsWhenConfiguredTokenIsBlank() throws Exception {
        InternalAuthFilter blankFilter = new InternalAuthFilter("", new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/inner/user/1");
        request.setServletPath("/inner/user/1");
        request.addHeader("X-Internal-Token", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        blankFilter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void skipsCheckForNonInnerPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/1");
        request.setServletPath("/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }

    @Test
    void forbiddenResponseKeepsResultJsonBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/inner/user/1");
        request.setServletPath("/inner/user/1");
        request.addHeader("X-Internal-Token", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("权限不足，禁止访问"));
    }

    @Test
    void rejectsWrongTokenWhenContextPathPresent() throws Exception {
        // 部署在 /ctx 下时 URI 为 /ctx/inner/x，必须按 servletPath=/inner/x 识别内部接口
        MockHttpServletRequest request = contextPathRequest();
        request.addHeader("X-Internal-Token", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void passesCorrectTokenWhenContextPathPresent() throws Exception {
        MockHttpServletRequest request = contextPathRequest();
        request.addHeader("X-Internal-Token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }

    /**
     * 构造带 context-path 的内部请求：requestURI=/ctx/inner/x，servletPath=/inner/x
     *
     * @return 模拟请求
     */
    private static MockHttpServletRequest contextPathRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ctx/inner/x");
        request.setContextPath("/ctx");
        request.setServletPath("/inner/x");
        return request;
    }
}
