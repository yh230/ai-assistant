package com.common.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 刷新令牌服务测试
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RefreshTokenStore store;

    @BeforeEach
    void setUp() {
        store = new RefreshTokenStore(stringRedisTemplate, 604800);
    }

    @Test
    void issueShouldWriteKeyWithMetadataAndTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String token = store.issue("staff", 1L);

        assertTrue(Pattern.matches("[0-9a-f]{32}", token));
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("auth:refresh:" + token), valueCaptor.capture(), eq(Duration.ofSeconds(604800)));
        String[] parts = valueCaptor.getValue().split(":");
        assertEquals("staff", parts[0]);
        assertEquals("1", parts[1]);
        assertTrue(Long.parseLong(parts[2]) > 0);
    }

    @Test
    void consumeShouldReturnInfoAndDeleteKey() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:refresh:t1")).thenReturn("patient:9:1700000000000");

        RefreshTokenStore.RefreshTokenInfo info = store.consume("t1");

        assertNotNull(info);
        assertEquals("patient", info.identity());
        assertEquals(9L, info.userId());
        assertEquals(1700000000000L, info.issuedAtMillis());
        verify(stringRedisTemplate).delete("auth:refresh:t1");
    }

    @Test
    void consumeShouldReturnNullWhenBlankOrMissing() {
        assertNull(store.consume(null));
        assertNull(store.consume(" "));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:refresh:t2")).thenReturn(null);
        assertNull(store.consume("t2"));
    }

    @Test
    void revokeShouldDeleteKeyAndSkipBlank() {
        store.revoke("t3");
        verify(stringRedisTemplate).delete("auth:refresh:t3");
        store.revoke("");
        store.revoke(null);
        verify(stringRedisTemplate, times(1)).delete(anyString());
    }
}
