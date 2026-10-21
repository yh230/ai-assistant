package com.mcp.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * MCP 调用方分钟级限流
 *
 * @author yh
 * @date 2026-10-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpRateLimiter {

    private final RedissonClient redissonClient;

    /**
     * 尝试获取一次调用配额
     *
     * @param consumer 调用方标识
     * @param limitPerMinute 每分钟上限
     * @return 是否允许本次调用
     */
    public boolean tryAcquire(String consumer, Integer limitPerMinute) {
        if (limitPerMinute == null || limitPerMinute <= 0) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60_000L;
        String key = "mcp:rate:" + consumer + ":" + minute;
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(key);
            long current = counter.incrementAndGet();
            if (current == 1) {
                counter.expire(Duration.ofSeconds(70));
            }
            return current <= limitPerMinute;
        } catch (Exception e) {
            log.warn("MCP 限流不可用，放行本次调用: consumer={}, {}", consumer, e.getMessage());
            return true;
        }
    }
}
