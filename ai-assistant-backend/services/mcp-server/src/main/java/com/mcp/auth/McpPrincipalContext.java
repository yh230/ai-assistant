package com.mcp.auth;

/**
 * 当前请求的 MCP 调用方上下文
 * <p>MCP Server 为 SYNC 模式，工具调用在请求线程内执行；过滤器在 finally 中清理。</p>
 *
 * @author yh
 * @date 2026-10-21
 */
public final class McpPrincipalContext {

    private static final ThreadLocal<McpPrincipal> HOLDER = new ThreadLocal<>();

    private McpPrincipalContext() {
    }

    /**
     * 写入当前调用方
     *
     * @param principal 调用方
     */
    public static void set(McpPrincipal principal) {
        HOLDER.set(principal);
    }

    /**
     * 获取当前调用方
     *
     * @return 调用方，未认证时为 null
     */
    public static McpPrincipal get() {
        return HOLDER.get();
    }

    /**
     * 清理当前调用方
     */
    public static void clear() {
        HOLDER.remove();
    }
}
