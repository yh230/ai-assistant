package com.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 对外服务配置
 *
 * @author yh
 * @date 2026-10-21
 */
@Data
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** 认证配置 */
    private Auth auth = new Auth();

    /** 缓存配置 */
    private Cache cache = new Cache();

    /**
     * API Key 认证配置
     */
    @Data
    public static class Auth {

        /** Key 列表 */
        private List<Key> keys = new ArrayList<>();
    }

    /**
     * 单个调用方 Key
     */
    @Data
    public static class Key {

        /** 调用方标识 */
        private String consumer;

        /** Key 的 SHA-256 十六进制值 */
        private String keySha256;

        /** 允许访问的医院 ID */
        private List<Long> hospitalIds = new ArrayList<>();

        /** 每分钟请求上限 */
        private Integer rateLimitPerMinute = 60;

        /** 是否启用 */
        private boolean enabled = true;
    }

    /**
     * 查询缓存配置
     */
    @Data
    public static class Cache {

        /** 缓存秒数，0 表示关闭 */
        private long ttlSeconds = 45;
    }
}
