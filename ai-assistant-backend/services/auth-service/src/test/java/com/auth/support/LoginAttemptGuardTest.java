package com.auth.support;

import com.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 登录失败锁定守卫测试
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class LoginAttemptGuardTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private LoginAttemptGuard guard;

    @BeforeEach
    void setUp() {
        guard = new LoginAttemptGuard(stringRedisTemplate, 5, 15, 15);
    }

    @Test
    void assertNotLockedShouldThrowWhenLockKeyExists() {
        when(stringRedisTemplate.hasKey("login:lock:staff:admin")).thenReturn(true);
        assertThrows(BusinessException.class, () -> guard.assertNotLocked("staff", "admin"));
    }

    @Test
    void assertNotLockedShouldPassWhenNoLock() {
        when(stringRedisTemplate.hasKey("login:lock:staff:admin")).thenReturn(false);
        assertDoesNotThrow(() -> guard.assertNotLocked("staff", "admin"));
    }

    @Test
    void recordFailureShouldSetWindowTtlOnFirstFailure() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("login:fail:staff:admin")).thenReturn(1L);
        guard.recordFailure("staff", "admin");
        verify(stringRedisTemplate).expire("login:fail:staff:admin", Duration.ofMinutes(15));
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void recordFailureShouldLockWhenReachingThreshold() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("login:fail:patient:13800000000")).thenReturn(5L);
        guard.recordFailure("patient", "13800000000");
        verify(valueOperations).set("login:lock:patient:13800000000", "1", Duration.ofMinutes(15));
    }

    @Test
    void resetShouldDeleteBothKeys() {
        guard.reset("staff", "admin");
        verify(stringRedisTemplate).delete(java.util.List.of("login:fail:staff:admin", "login:lock:staff:admin"));
    }
}
