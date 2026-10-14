package com.common.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;

/**
 * 刷新令牌服务（Redis 白名单）：随机串按 tokenId 落键，值携带身份/用户/签发时刻
 * <p>
 * 键 {@code auth:refresh:{tokenId}} → 值 {@code {identity}:{userId}:{issuedAtMillis}}，TTL 默认 7 天；
 * 刷新即轮换（consume 读取并删除），登出/踢下线按需撤销
 *
 * @author yh
 * @date 2026-10-14
 */
@Component
public class RefreshTokenStore {

    /** 刷新令牌键前缀 */
    private static final String REFRESH_PREFIX = "auth:refresh:";

    private final StringRedisTemplate stringRedisTemplate;
    private final long refreshExpirationSeconds;

    /**
     * 构造刷新令牌服务
     *
     * @param stringRedisTemplate Redis 客户端
     * @param refreshExpirationSeconds 令牌有效期（秒）
     */
    public RefreshTokenStore(StringRedisTemplate stringRedisTemplate,
                             @Value("${security.jwt.refresh-expiration-seconds:604800}") long refreshExpirationSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.refreshExpirationSeconds = refreshExpirationSeconds;
    }

    /**
     * 签发新刷新令牌（32 位十六进制随机串，非 JWT）
     *
     * @param identity 身份类型（staff/patient）
     * @param userId 用户 ID
     * @return 刷新令牌串
     */
    public String issue(String identity, Long userId) {
        String tokenId = UUID.randomUUID().toString().replace("-", "");
        String value = identity + ":" + userId + ":" + System.currentTimeMillis();
        stringRedisTemplate.opsForValue().set(REFRESH_PREFIX + tokenId, value,
                Duration.ofSeconds(refreshExpirationSeconds));
        return tokenId;
    }

    /**
     * 消费刷新令牌（读取并删除，供轮换使用）
     *
     * @param token 刷新令牌串
     * @return 令牌信息；不存在或格式异常返回 null
     */
    public RefreshTokenInfo consume(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String key = REFRESH_PREFIX + token;
        String value = stringRedisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new RefreshTokenInfo(parts[0], Long.valueOf(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 撤销刷新令牌（登出/兜底，幂等）
     *
     * @param token 刷新令牌串
     */
    public void revoke(String token) {
        if (StringUtils.hasText(token)) {
            stringRedisTemplate.delete(REFRESH_PREFIX + token);
        }
    }

    /** 刷新令牌信息（身份 / 用户 / 签发时刻毫秒） */
    public record RefreshTokenInfo(String identity, Long userId, long issuedAtMillis) {
    }
}
