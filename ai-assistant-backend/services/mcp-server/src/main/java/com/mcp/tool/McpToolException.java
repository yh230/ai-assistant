package com.mcp.tool;

/**
 * MCP 工具业务异常
 * <p>由 McpToolUtils 捕获并转换为 {@code CallToolResult(isError=true)}，
 * 调用方据此区分业务错误与正常结果。</p>
 *
 * @author yh
 * @date 2026-10-21
 */
public class McpToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造工具业务异常
     *
     * @param message 对外错误文案
     */
    public McpToolException(String message) {
        super(message);
    }
}
