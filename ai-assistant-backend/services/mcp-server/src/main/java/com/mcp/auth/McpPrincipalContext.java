package com.mcp.auth;

import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Map;

/**
 * 当前 MCP 调用方上下文
 * <p>认证过滤器把 Principal 写入请求属性；SSE 传输提取为 McpTransportContext；
 * 工具通过 ToolContext 中的 McpSyncServerExchange 读取。</p>
 *
 * @author yh
 * @date 2026-10-21
 */
public final class McpPrincipalContext {

    /** 请求属性名 */
    public static final String REQUEST_ATTRIBUTE = McpPrincipalContext.class.getName() + ".principal";

    /** 传输上下文键 */
    public static final String TRANSPORT_KEY = "mcp.principal";

    private McpPrincipalContext() {
    }

    /**
     * 把调用方写入当前 HTTP 请求
     *
     * @param request HTTP 请求
     * @param principal 调用方
     */
    public static void attach(HttpServletRequest request, McpPrincipal principal) {
        request.setAttribute(REQUEST_ATTRIBUTE, principal);
    }

    /**
     * 从 HTTP 请求提取传输上下文
     *
     * @param request Spring MVC 函数式请求
     * @return 传输上下文
     */
    public static McpTransportContext extract(ServerRequest request) {
        Object principal = request.servletRequest().getAttribute(REQUEST_ATTRIBUTE);
        if (principal instanceof McpPrincipal value) {
            return McpTransportContext.create(Map.of(TRANSPORT_KEY, value));
        }
        return McpTransportContext.EMPTY;
    }

    /**
     * 从工具上下文读取调用方
     *
     * @param toolContext Spring AI 工具上下文
     * @return 调用方，缺失时为 null
     */
    public static McpPrincipal from(ToolContext toolContext) {
        return McpToolUtils.getMcpExchange(toolContext)
                .map(exchange -> exchange.transportContext().get(TRANSPORT_KEY))
                .filter(McpPrincipal.class::isInstance)
                .map(McpPrincipal.class::cast)
                .orElse(null);
    }
}
