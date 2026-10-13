package com.common.jwt;

import com.common.constant.SecurityConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 令牌撤销服务（Redis）：登出黑名单（按 jti）与踢下线（按 userId 的签发时间阈值）
 * <p>
 * 踢下线语义 = 拒绝"签发时间不晚于踢出时刻"的所有 token（重新登录的新 token 不受影响）
 * <p>
 * 边界说明（2 条）：
 * <ul>
 *     <li>踢下线判定为"签发时间 > 踢出时间"；因 JWT iat 为秒级截断，踢出与重新登录发生在同一秒时，
 *         新 token 可能被误判失效（窗口 ≤1s，fail-closed，晚 1 秒重新登录即自愈）</li>
 *     <li>{@link #KICK_KEY_TTL}（24h）需大于令牌有效期；若未来调大 {@code security.jwt.expiration-seconds}
 *         超过 24h，需同步调整该常量</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-12
 */
@Component
@RequiredArgsConstructor
public class TokenRevocationService {

    /** 登出黑名单键前缀（值固定为 1，TTL=令牌剩余有效期） */
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    /** 踢下线阈值键前缀（值为踢出时刻毫秒时间戳） */
    private static final String KICK_PREFIX = "jwt:invalid-after:";

    /** 踢下线键 TTL：需大于令牌最大有效期（默认 1h），过期自动清理 */
    private static final Duration KICK_KEY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * 撤销单个令牌（登出）
     *
     * @param jti 令牌唯一标识
     * @param expiresAtMillis 令牌过期时间（毫秒，用于黑名单 TTL）
     */
    public void revokeToken(String jti, long expiresAtMillis) {
        long ttlMillis = expiresAtMillis - System.currentTimeMillis();
        if (ttlMillis > 0) {
            redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "1", Duration.ofMillis(ttlMillis));
        }
    }

    /**
     * 判断令牌是否已被登出撤销
     *
     * @param jti 令牌唯一标识
     * @return true=已撤销
     */
    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }

    /**
     * 踢下线：使该身份下该用户当前全部有效令牌即时失效
     *
     * @param identity 身份类型（staff/patient）
     * @param userId 用户 ID
     */
    public void kickout(String identity, Long userId) {
        redisTemplate.opsForValue().set(kickKey(identity, userId),
                String.valueOf(System.currentTimeMillis()), KICK_KEY_TTL);
    }

    /**
     * 踢下线：使该用户当前全部有效令牌即时失效
     *
     * <p>员工默认身份快捷方法</p>
     *
     * @param userId 用户 ID
     */
    public void kickout(Long userId) {
        kickout(SecurityConstants.IDENTITY_STAFF, userId);
    }

    /**
     * 查询用户被踢时间戳
     *
     * @param identity 身份类型（staff/patient）
     * @param userId 用户 ID
     * @return 被踢时刻（毫秒）；从未被踢返回 0
     */
    public long kickoutAt(String identity, Long userId) {
        String value = redisTemplate.opsForValue().get(kickKey(identity, userId));
        if (value == null && SecurityConstants.IDENTITY_STAFF.equals(identity)) {
            value = redisTemplate.opsForValue().get(KICK_PREFIX + userId);
        }
        return value == null ? 0L : Long.parseLong(value);
    }

    /**
     * 查询用户被踢时间戳
     *
     * <p>员工默认身份快捷方法</p>
     *
     * @param userId 用户 ID
     * @return 被踢时刻（毫秒）；从未被踢返回 0
     */
    public long kickoutAt(Long userId) {
        return kickoutAt(SecurityConstants.IDENTITY_STAFF, userId);
    }

    private String kickKey(String identity, Long userId) {
        return KICK_PREFIX + identity + ":" + userId;
    }
}
