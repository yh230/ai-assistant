package com.common.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * 刷新令牌服务（Redis 白名单）：随机串按 tokenId 落键，值携带身份/用户/签发时刻/令牌族
 * <p>
 * 键 {@code auth:refresh:{tokenId}} → 值 {@code {identity}:{userId}:{issuedAtMillis}:{familyId}}，TTL 默认 7 天；
 * 刷新即轮换（consume 读取并删除），登出/踢下线按需撤销。已消费令牌保留墓碑键，
 * 再次出现即视为令牌复用，整族撤销；令牌族带 30 天绝对有效期，避免无限滑续。
 *
 * @author yh
 * @date 2026-10-14
 */
@Component
public class RefreshTokenStore {

    /** 刷新令牌键前缀 */
    private static final String REFRESH_PREFIX = "auth:refresh:";
    /** 已消费令牌墓碑前缀（值=familyId，用于复用检测） */
    private static final String USED_PREFIX = "auth:refresh:used:";
    /** 令牌族成员集合前缀 */
    private static final String FAMILY_PREFIX = "auth:refresh:family:";
    /** 令牌族绝对过期时间前缀 */
    private static final String FAMILY_ABS_PREFIX = "auth:refresh:family:abs:";
    /** 用户会话索引前缀（集合成员 tokenId|familyId，供登出撤销全部会话） */
    private static final String USER_PREFIX = "auth:refresh:user:";

    private final StringRedisTemplate stringRedisTemplate;
    private final long refreshExpirationSeconds;
    private final long absoluteExpirationSeconds;

    /**
     * 构造刷新令牌服务
     *
     * @param stringRedisTemplate Redis 客户端
     * @param refreshExpirationSeconds 单令牌有效期（秒）
     * @param absoluteExpirationSeconds 令牌族绝对有效期（秒）
     */
    public RefreshTokenStore(StringRedisTemplate stringRedisTemplate,
                             @Value("${security.jwt.refresh-expiration-seconds:604800}") long refreshExpirationSeconds,
                             @Value("${security.jwt.refresh-absolute-seconds:2592000}") long absoluteExpirationSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.refreshExpirationSeconds = refreshExpirationSeconds;
        this.absoluteExpirationSeconds = absoluteExpirationSeconds;
    }

    /**
     * 签发新刷新令牌（新令牌族）
     *
     * @param identity 身份类型（staff/patient）
     * @param userId 用户 ID
     * @return 刷新令牌串
     */
    public String issue(String identity, Long userId) {
        String familyId = newId();
        stringRedisTemplate.opsForValue().set(FAMILY_ABS_PREFIX + familyId,
                String.valueOf(System.currentTimeMillis() + absoluteExpirationSeconds * 1000),
                Duration.ofSeconds(absoluteExpirationSeconds));
        return issueInFamily(identity, userId, familyId);
    }

    /**
     * 在现有令牌族内轮换刷新令牌
     *
     * @param identity 身份类型
     * @param userId 用户 ID
     * @param familyId 令牌族 ID
     * @return 新刷新令牌；令牌族已过期/不存在返回 null
     */
    public String rotate(String identity, Long userId, String familyId) {
        if (!StringUtils.hasText(familyId)) {
            return null;
        }
        String absolute = stringRedisTemplate.opsForValue().get(FAMILY_ABS_PREFIX + familyId);
        if (!StringUtils.hasText(absolute)) {
            return null;
        }
        try {
            if (System.currentTimeMillis() > Long.parseLong(absolute)) {
                revokeFamily(familyId);
                return null;
            }
        } catch (NumberFormatException e) {
            revokeFamily(familyId);
            return null;
        }
        return issueInFamily(identity, userId, familyId);
    }

    /**
     * 消费刷新令牌（读取并删除，供轮换使用）
     *
     * @param token 刷新令牌串
     * @return 令牌信息；不存在、已消费或格式异常返回 null（已消费令牌会触发整族撤销）
     */
    public RefreshTokenInfo consume(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().getAndDelete(REFRESH_PREFIX + token);
        if (value == null) {
            // 令牌键已不存在：若存在墓碑则说明旧令牌被重放，撤销整个令牌族
            String usedFamily = stringRedisTemplate.opsForValue().get(USED_PREFIX + token);
            if (StringUtils.hasText(usedFamily)) {
                revokeFamily(usedFamily);
            }
            return null;
        }
        String[] parts = value.split(":");
        if (parts.length != 3 && parts.length != 4) {
            return null;
        }
        try {
            Long userId = Long.valueOf(parts[1]);
            long issuedAt = Long.parseLong(parts[2]);
            String familyId = parts.length == 4 ? parts[3] : null;
            if (familyId != null) {
                stringRedisTemplate.opsForValue().set(USED_PREFIX + token, familyId,
                        Duration.ofSeconds(refreshExpirationSeconds));
                stringRedisTemplate.opsForSet().remove(FAMILY_PREFIX + familyId, token);
                stringRedisTemplate.opsForSet().remove(USER_PREFIX + parts[0] + ":" + userId,
                        token + "|" + familyId);
            }
            return new RefreshTokenInfo(parts[0], userId, issuedAt, familyId);
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

    /**
     * 撤销指定用户的全部刷新令牌（登出未携带 RT 时兜底）
     *
     * @param identity 身份类型
     * @param userId 用户 ID
     */
    public void revokeByUser(String identity, Long userId) {
        if (!StringUtils.hasText(identity) || userId == null) {
            return;
        }
        String userKey = USER_PREFIX + identity + ":" + userId;
        Set<String> members = stringRedisTemplate.opsForSet().members(userKey);
        if (members != null) {
            for (String member : members) {
                int idx = member.indexOf('|');
                String tokenId = idx > 0 ? member.substring(0, idx) : member;
                stringRedisTemplate.delete(REFRESH_PREFIX + tokenId);
            }
        }
        stringRedisTemplate.delete(userKey);
    }

    private String issueInFamily(String identity, Long userId, String familyId) {
        String tokenId = newId();
        String value = identity + ":" + userId + ":" + System.currentTimeMillis() + ":" + familyId;
        stringRedisTemplate.opsForValue().set(REFRESH_PREFIX + tokenId, value,
                Duration.ofSeconds(refreshExpirationSeconds));
        String familyKey = FAMILY_PREFIX + familyId;
        stringRedisTemplate.opsForSet().add(familyKey, tokenId);
        stringRedisTemplate.expire(familyKey, Duration.ofSeconds(refreshExpirationSeconds));
        String userKey = USER_PREFIX + identity + ":" + userId;
        stringRedisTemplate.opsForSet().add(userKey, tokenId + "|" + familyId);
        stringRedisTemplate.expire(userKey, Duration.ofSeconds(refreshExpirationSeconds));
        return tokenId;
    }

    private void revokeFamily(String familyId) {
        String familyKey = FAMILY_PREFIX + familyId;
        Set<String> members = stringRedisTemplate.opsForSet().members(familyKey);
        if (members != null) {
            for (String tokenId : members) {
                stringRedisTemplate.delete(REFRESH_PREFIX + tokenId);
            }
        }
        stringRedisTemplate.delete(familyKey);
        stringRedisTemplate.delete(FAMILY_ABS_PREFIX + familyId);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 刷新令牌信息（身份 / 用户 / 签发时刻毫秒 / 令牌族，旧格式令牌 familyId 为空） */
    public record RefreshTokenInfo(String identity, Long userId, long issuedAtMillis, String familyId) {
    }
}
