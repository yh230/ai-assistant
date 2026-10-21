package com.mcp.config;

import com.mcp.tool.ScheduleTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册
 *
 * @author yh
 * @date 2026-10-21
 */
@Configuration
public class McpToolConfig {

    /**
     * 将排班工具注册给 MCP Server
     *
     * @param tools 排班工具
     * @return 工具回调提供者
     */
    @Bean
    public ToolCallbackProvider scheduleToolCallbackProvider(ScheduleTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
