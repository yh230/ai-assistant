package com.auth.support;

import com.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @Mock private SetOperations<String, String> setOperations;

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private LoginAttemptGuard guard;

    @BeforeEach
    void setUp() {
        guard = new LoginAttemptGuard(stringRedisTemplate, 5, 15, 15, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
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

    @Test
    void singleIpFailuresLockIpInsteadOfAccount() {
        bindIp("10.0.0.8");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(valueOperations.increment("login:fail:staff:admin")).thenReturn(5L);
        when(valueOperations.increment("login:fail:ip:staff:10.0.0.8")).thenReturn(15L);
        when(setOperations.size("login:ips:staff:admin")).thenReturn(1L);

        guard.recordFailure("staff", "admin");

        verify(valueOperations).set("login:lock:ip:staff:10.0.0.8", "1", Duration.ofMinutes(5));
        verify(valueOperations, never()).set(eq("login:lock:staff:admin"), anyString(), any(Duration.class));
    }

    @Test
    void failuresFromTwoIpsLockAccount() {
        bindIp("10.0.0.8");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(valueOperations.increment("login:fail:staff:admin")).thenReturn(6L);
        when(valueOperations.increment("login:fail:ip:staff:10.0.0.8")).thenReturn(1L);
        when(setOperations.size("login:ips:staff:admin")).thenReturn(2L);

        guard.recordFailure("staff", "admin");

        verify(valueOperations).set("login:lock:staff:admin", "1", Duration.ofMinutes(15));
    }

    @Test
    void assertNotLockedShouldCheckIpLock() {
        bindIp("10.0.0.8");
        when(stringRedisTemplate.hasKey("login:lock:staff:admin")).thenReturn(false);
        when(stringRedisTemplate.hasKey("login:lock:ip:staff:10.0.0.8")).thenReturn(true);

        assertThrows(BusinessException.class, () -> guard.assertNotLocked("staff", "admin"));
    }

    @Test
    void spoofedForwardedForPrefixIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(stringRedisTemplate.hasKey("login:lock:staff:admin")).thenReturn(false);
        when(stringRedisTemplate.hasKey("login:lock:ip:staff:10.0.0.8")).thenReturn(true);

        assertThrows(BusinessException.class, () -> guard.assertNotLocked("staff", "admin"));
    }

    @Test
    void privateRemoteAddrWithoutForwardedForIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(stringRedisTemplate.hasKey("login:lock:staff:admin")).thenReturn(false);

        assertDoesNotThrow(() -> guard.assertNotLocked("staff", "admin"));
        verify(stringRedisTemplate, never()).hasKey(startsWith("login:lock:ip:"));
    }

    private void bindIp(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 模拟网关转发：remoteAddr 为网关内网地址，真实来源在 XFF 末段
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("X-Forwarded-For", ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
