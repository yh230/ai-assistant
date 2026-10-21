package com.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 配置绑定测试
 *
 * @author yh
 * @date 2026-10-21
 */
class McpPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "mcp.auth.keys[0].consumer=demo",
                    "mcp.auth.keys[0].key-sha256=abc",
                    "mcp.auth.keys[0].hospital-ids=1,2",
                    "mcp.auth.keys[0].rate-limit-per-minute=10",
                    "mcp.cache.ttl-seconds=30");

    @Test
    void bindsNestedConfiguration() {
        runner.run(context -> {
            McpProperties properties = context.getBean(McpProperties.class);
            assertEquals(1, properties.getAuth().getKeys().size());
            assertEquals("demo", properties.getAuth().getKeys().get(0).getConsumer());
            assertEquals(java.util.List.of(1L, 2L), properties.getAuth().getKeys().get(0).getHospitalIds());
            assertEquals(10, properties.getAuth().getKeys().get(0).getRateLimitPerMinute());
            assertTrue(properties.getAuth().getKeys().get(0).isEnabled());
            assertEquals(30, properties.getCache().getTtlSeconds());
        });
    }

    @Configuration
    @EnableConfigurationProperties(McpProperties.class)
    static class TestConfig {
    }
}
