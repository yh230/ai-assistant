package com.common.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 刷新令牌服务测试（令牌族轮换、复用检测、按用户撤销）
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SetOperations<String, String> setOperations;

    private RefreshTokenStore store;

    @BeforeEach
    void setUp() {
        store = new RefreshTokenStore(stringRedisTemplate, 604800, 2592000);
    }

    @Test
    void issueShouldWriteTokenFamilyAndUserIndex() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        String token = store.issue("staff", 1L);

        assertTrue(Pattern.matches("[0-9a-f]{32}", token));
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("auth:refresh:" + token), valueCaptor.capture(),
                eq(Duration.ofSeconds(604800)));
        String[] parts = valueCaptor.getValue().split(":");
        assertEquals(4, parts.length);
        assertEquals("staff", parts[0]);
        assertEquals("1", parts[1]);
        assertTrue(Long.parseLong(parts[2]) > 0);
        assertTrue(Pattern.matches("[0-9a-f]{32}", parts[3]));
        verify(setOperations).add("auth:refresh:family:" + parts[3], token);
        verify(setOperations).add("auth:refresh:user:staff:1", token + "|" + parts[3]);
        verify(stringRedisTemplate).expire("auth:refresh:family:" + parts[3], Duration.ofSeconds(604800));
        verify(stringRedisTemplate).expire("auth:refresh:user:staff:1", Duration.ofSeconds(604800));
    }

    @Test
    void consumeShouldReturnInfoAndLeaveReuseTombstone() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(valueOperations.getAndDelete("auth:refresh:t1"))
                .thenReturn("patient:9:1700000000000:fam-9");

        RefreshTokenStore.RefreshTokenInfo info = store.consume("t1");

        assertNotNull(info);
        assertEquals("patient", info.identity());
        assertEquals(9L, info.userId());
        assertEquals(1700000000000L, info.issuedAtMillis());
        assertEquals("fam-9", info.familyId());
        verify(valueOperations).set("auth:refresh:used:t1", "fam-9", Duration.ofSeconds(604800));
        verify(setOperations).remove("auth:refresh:family:fam-9", "t1");
        verify(setOperations).remove("auth:refresh:user:patient:9", "t1|fam-9");
    }

    @Test
    void reusedTokenShouldRevokeWholeFamily() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("auth:refresh:t1")).thenReturn(null);
        when(valueOperations.get("auth:refresh:used:t1")).thenReturn("fam-9");
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("auth:refresh:family:fam-9")).thenReturn(Set.of("t2", "t3"));

        assertNull(store.consume("t1"));

        verify(stringRedisTemplate).delete("auth:refresh:t2");
        verify(stringRedisTemplate).delete("auth:refresh:t3");
        verify(stringRedisTemplate).delete("auth:refresh:family:fam-9");
        verify(stringRedisTemplate).delete("auth:refresh:family:abs:fam-9");
    }

    @Test
    void consumeShouldReturnNullWhenBlankOrMissing() {
        assertNull(store.consume(null));
        assertNull(store.consume(" "));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("auth:refresh:t2")).thenReturn(null);
        when(valueOperations.get("auth:refresh:used:t2")).thenReturn(null);
        assertNull(store.consume("t2"));
    }

    @Test
    void consumeShouldReturnNullForMalformedValue() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("auth:refresh:t4")).thenReturn("staff");
        when(valueOperations.getAndDelete("auth:refresh:t5")).thenReturn("staff:abc:1");

        assertNull(store.consume("t4"));
        assertNull(store.consume("t5"));
    }

    @Test
    void rotateShouldRejectExpiredOrMissingFamily() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        assertNull(store.rotate("staff", 1L, null));
        when(valueOperations.get("auth:refresh:family:abs:fam-x")).thenReturn(null);
        assertNull(store.rotate("staff", 1L, "fam-x"));
        when(valueOperations.get("auth:refresh:family:abs:fam-y")).thenReturn("1000");
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("auth:refresh:family:fam-y")).thenReturn(Set.of());
        assertNull(store.rotate("staff", 1L, "fam-y"));
    }

    @Test
    void revokeShouldDeleteKeyAndSkipBlank() {
        store.revoke("t3");
        verify(stringRedisTemplate).delete("auth:refresh:t3");
        store.revoke("");
        store.revoke(null);
        verify(stringRedisTemplate, times(1)).delete(anyString());
    }

    @Test
    void revokeByUserShouldDeleteAllIndexedTokens() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("auth:refresh:user:staff:1"))
                .thenReturn(Set.of("t1|fam-1", "t2|fam-2"));

        store.revokeByUser("staff", 1L);

        verify(stringRedisTemplate).delete("auth:refresh:t1");
        verify(stringRedisTemplate).delete("auth:refresh:t2");
        verify(stringRedisTemplate).delete("auth:refresh:user:staff:1");
    }

    @Test
    void revokeByUserShouldIgnoreBlankIdentityOrNullUser() {
        store.revokeByUser("", 1L);
        store.revokeByUser("staff", null);
        verify(stringRedisTemplate, never()).opsForSet();
    }
}
