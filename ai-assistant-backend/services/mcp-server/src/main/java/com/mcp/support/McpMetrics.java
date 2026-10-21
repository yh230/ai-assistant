package com.mcp.support;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * MCP 访问指标
 *
 * @author yh
 * @date 2026-10-21
 */
@Component
@RequiredArgsConstructor
public class McpMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 记录工具调用
     *
     * @param tool 工具名
     * @param outcome 结果
     * @param nanos 耗时（纳秒）
     */
    public void toolCall(String tool, String outcome, long nanos) {
        meterRegistry.counter("mcp.tool.calls", "tool", tool, "outcome", outcome).increment();
        meterRegistry.timer("mcp.tool.duration", "tool", tool).record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录认证失败
     *
     * @param reason 原因
     */
    public void authFailure(String reason) {
        meterRegistry.counter("mcp.auth.failures", "reason", reason).increment();
    }

    /**
     * 记录限流
     *
     * @param consumer 调用方
     */
    public void rateLimited(String consumer) {
        meterRegistry.counter("mcp.rate.limited", "consumer", consumer).increment();
    }
}
