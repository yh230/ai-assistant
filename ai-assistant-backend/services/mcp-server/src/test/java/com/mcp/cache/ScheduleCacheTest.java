package com.mcp.cache;

import com.api.dto.ScheduleQueryResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.config.McpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 排班缓存测试
 *
 * @author yh
 * @date 2026-10-21
 */
class ScheduleCacheTest {

    @Test
    void returnsEmptyWhenRedisFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString())).thenThrow(new RuntimeException("redis down"));
        ScheduleCache cache = new ScheduleCache(redis, new ObjectMapper(), new McpProperties());

        assertTrue(cache.get("k").isEmpty());
    }

    @Test
    void writesJsonWithConfiguredTtl() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        McpProperties properties = new McpProperties();
        properties.getCache().setTtlSeconds(45);
        ScheduleCache cache = new ScheduleCache(redis, new ObjectMapper(), properties);
        ScheduleQueryResultDTO value = ScheduleQueryResultDTO.empty();

        cache.put("k", value);

        verify(operations).set(eq("k"), anyString(), eq(Duration.ofSeconds(45)));
    }

    @Test
    void readsCachedValue() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        ObjectMapper objectMapper = new ObjectMapper();
        when(operations.get("k")).thenReturn(objectMapper.writeValueAsString(ScheduleQueryResultDTO.empty()));
        ScheduleCache cache = new ScheduleCache(redis, objectMapper, new McpProperties());

        Optional<ScheduleQueryResultDTO> result = cache.get("k");

        assertTrue(result.isPresent());
        assertEquals(0, result.get().getItems().size());
    }
}
