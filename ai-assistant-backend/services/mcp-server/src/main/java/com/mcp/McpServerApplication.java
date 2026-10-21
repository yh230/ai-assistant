package com.mcp;

import com.mcp.config.McpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 对外排班查询 MCP Server 启动类
 *
 * @author yh
 * @date 2026-10-21
 */
@EnableConfigurationProperties(McpProperties.class)
@EnableFeignClients("com.api.client")
@SpringBootApplication(scanBasePackages = {"com.mcp", "com.api"})
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
