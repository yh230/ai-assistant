package com.mcp.cache;

import com.api.dto.ScheduleQueryResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.config.McpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * MCP 排班查询缓存
 *
 * @author yh
 * @date 2026-10-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleCache {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final McpProperties properties;

    /**
     * 读取缓存
     *
     * @param key 缓存键
     * @return 缓存值，未命中或异常时为空
     */
    public Optional<ScheduleQueryResultDTO> get(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ScheduleQueryResultDTO.class));
        } catch (Exception e) {
            log.warn("MCP 缓存读取失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 写入缓存
     *
     * @param key 缓存键
     * @param value 查询结果
     */
    public void put(String key, ScheduleQueryResultDTO value) {
        long ttlSeconds = properties.getCache().getTtlSeconds();
        if (ttlSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key, objectMapper.writeValueAsString(value), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("MCP 缓存写入失败: {}", e.getMessage());
        }
    }
}
