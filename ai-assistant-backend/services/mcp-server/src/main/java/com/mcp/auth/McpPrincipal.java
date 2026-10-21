package com.mcp.auth;

import java.util.List;

/**
 * 已认证的 MCP 调用方
 *
 * @param consumer 调用方标识
 * @param hospitalIds 允许访问的医院 ID
 * @param rateLimitPerMinute 每分钟请求上限
 * @author yh
 * @date 2026-10-21
 */
public record McpPrincipal(String consumer, List<Long> hospitalIds, int rateLimitPerMinute) {

    /**
     * 复制医院列表，避免调用方持有可变引用
     *
     * @param consumer 调用方标识
     * @param hospitalIds 允许访问的医院 ID
     * @param rateLimitPerMinute 每分钟请求上限
     */
    public McpPrincipal {
        hospitalIds = List.copyOf(hospitalIds);
    }

    /**
     * 判断医院是否在授权范围内
     *
     * @param hospitalId 医院 ID
     * @return 是否允许访问
     */
    public boolean canAccess(Long hospitalId) {
        return hospitalId != null && hospitalIds.contains(hospitalId);
    }
}
