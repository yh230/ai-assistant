package com.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.auth.McpPrincipalContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import org.springframework.ai.mcp.server.autoconfigure.McpServerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * MCP SSE 传输配置
 *
 * @author yh
 * @date 2026-10-21
 */
@Configuration
public class McpTransportConfig {

    /**
     * 创建带调用方上下文提取的 SSE 传输提供者
     *
     * @param objectMapper MCP 专用 ObjectMapper
     * @param properties MCP 服务端配置
     * @return SSE 传输提供者
     */
    @Bean
    public WebMvcSseServerTransportProvider mcpTransportProvider(
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper,
            McpServerProperties properties) {
        return WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .baseUrl(properties.getBaseUrl())
                .sseEndpoint(properties.getSseEndpoint())
                .messageEndpoint(properties.getSseMessageEndpoint())
                .contextExtractor(McpPrincipalContext::extract)
                .build();
    }

    /**
     * 注册 MCP SSE 路由
     *
     * @param provider SSE 传输提供者
     * @return 路由函数
     */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransportProvider provider) {
        return provider.getRouterFunction();
    }
}
