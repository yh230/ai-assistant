package com.mcp.auth;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 调用方上下文传递测试
 *
 * @author yh
 * @date 2026-10-21
 */
class McpPrincipalContextTest {

    @Test
    void extractsPrincipalFromServletRequestAttribute() {
        McpPrincipal principal = new McpPrincipal("demo", List.of(1L), 60);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/mcp/message");
        servletRequest.setAttribute(McpPrincipalContext.REQUEST_ATTRIBUTE, principal);
        ServerRequest request = ServerRequest.create(servletRequest, List.of());

        McpTransportContext context = McpPrincipalContext.extract(request);

        assertSame(principal, context.get(McpPrincipalContext.TRANSPORT_KEY));
    }

    @Test
    void extractsEmptyContextWhenAttributeMissing() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/mcp/message");
        ServerRequest request = ServerRequest.create(servletRequest, List.of());

        McpTransportContext context = McpPrincipalContext.extract(request);

        assertSame(McpTransportContext.EMPTY, context);
    }

    @Test
    void readsPrincipalFromToolContextExchange() {
        McpPrincipal principal = new McpPrincipal("demo", List.of(1L, 2L), 60);
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(McpPrincipalContext.TRANSPORT_KEY, principal)));
        ToolContext toolContext = new ToolContext(
                Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));

        assertSame(principal, McpPrincipalContext.from(toolContext));
    }

    @Test
    void returnsNullWhenToolContextIsEmpty() {
        assertNull(McpPrincipalContext.from(new ToolContext(Map.of())));
    }

    @Test
    void returnsNullWhenTransportContextHasNoPrincipal() {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpTransportContext.EMPTY);
        ToolContext toolContext = new ToolContext(
                Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));

        assertNull(McpPrincipalContext.from(toolContext));
    }
}
