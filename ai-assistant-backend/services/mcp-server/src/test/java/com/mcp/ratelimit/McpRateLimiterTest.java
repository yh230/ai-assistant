package com.mcp.ratelimit;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 限流器测试
 *
 * @author yh
 * @date 2026-10-21
 */
class McpRateLimiterTest {

    @Test
    void allowsWithinLimitAndRejectsBeyondLimit() {
        RedissonClient redisson = mock(RedissonClient.class);
        RAtomicLong counter = mock(RAtomicLong.class);
        when(redisson.getAtomicLong(anyString())).thenReturn(counter);
        when(counter.incrementAndGet()).thenReturn(1L, 2L);
        when(counter.expire(any(Duration.class))).thenReturn(true);
        McpRateLimiter limiter = new McpRateLimiter(redisson);

        assertTrue(limiter.tryAcquire("demo", 1));
        assertFalse(limiter.tryAcquire("demo", 1));
    }

    @Test
    void failsOpenWhenRedisUnavailable() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getAtomicLong(anyString())).thenThrow(new RuntimeException("redis down"));
        McpRateLimiter limiter = new McpRateLimiter(redisson);

        assertTrue(limiter.tryAcquire("demo", 1));
    }
}
