package com.common.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 消费幂等守卫测试
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class IdempotentGuardTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private IdempotentGuard guard;

    @BeforeEach
    void setUp() {
        guard = new IdempotentGuard(stringRedisTemplate);
    }

    @Test
    void consumeOnceShouldReturnTrueOnFirstSet() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("mq:idem:notification-consumer:e1"), eq("1"),
                eq(Duration.ofDays(7)))).thenReturn(true);

        assertTrue(guard.consumeOnce("notification-consumer", "e1", Duration.ofDays(7)));
    }

    @Test
    void consumeOnceShouldReturnFalseWhenAlreadySeen() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("mq:idem:audit-consumer:e2"), eq("1"),
                eq(Duration.ofDays(7)))).thenReturn(false);

        assertFalse(guard.consumeOnce("audit-consumer", "e2", Duration.ofDays(7)));
    }

    @Test
    void consumeOnceShouldReturnTrueOnRedisErrorToAvoidMessageLoss() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertTrue(guard.consumeOnce("audit-consumer", "e3", Duration.ofDays(7)));
    }

    @Test
    void consumeOnceShouldSkipBlankEventId() {
        assertTrue(guard.consumeOnce("audit-consumer", " ", Duration.ofDays(7)));
    }

    @Test
    void releaseShouldDeleteKey() {
        guard.release("audit-consumer", "e4");
        verify(stringRedisTemplate).delete("mq:idem:audit-consumer:e4");
    }

    @Test
    void releaseShouldSkipBlankEventId() {
        guard.release("audit-consumer", " ");
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void releaseShouldSwallowRedisError() {
        when(stringRedisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));
        assertDoesNotThrow(() -> guard.release("audit-consumer", "e5"));
    }
}
