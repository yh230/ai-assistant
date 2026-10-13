package com.common.auth;

import com.common.constant.SecurityConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户权限快照存取（员工 auth:user:{userId} / 患者 auth:patient:{userId}，TTL 30 分钟）
 *
 * @author yh
 * @date 2026-10-12
 */
@Component
@RequiredArgsConstructor
public class UserSnapshotStore {

    private static final String STAFF_KEY_PREFIX = "auth:user:";
    private static final String PATIENT_KEY_PREFIX = "auth:patient:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 保存快照（按身份选 key，覆盖写并续期） */
    public void save(UserSnapshot snapshot) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(key(snapshot.getIdentity(), snapshot.getUserId()),
                            objectMapper.writeValueAsString(snapshot), TTL);
        } catch (Exception e) {
            throw new IllegalStateException("序列化用户快照失败: userId=" + snapshot.getUserId(), e);
        }
    }

    /**
     * 读取快照（不存在返回 null）
     *
     * <p>员工默认身份快捷方法</p>
     *
     * @param userId 用户 ID
     * @return 快照；不存在返回 null
     */
    public UserSnapshot get(Long userId) {
        return get(SecurityConstants.IDENTITY_STAFF, userId);
    }

    /** 读取快照（不存在返回 null） */
    public UserSnapshot get(String identity, Long userId) {
        String json = stringRedisTemplate.opsForValue().get(key(identity, userId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, UserSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化用户快照失败: userId=" + userId, e);
        }
    }

    /**
     * 失效快照
     *
     * <p>员工默认身份快捷方法</p>
     *
     * @param userId 用户 ID
     */
    public void evict(Long userId) {
        evict(SecurityConstants.IDENTITY_STAFF, userId);
    }

    /** 失效快照 */
    public void evict(String identity, Long userId) {
        stringRedisTemplate.delete(key(identity, userId));
    }

    private String key(String identity, Long userId) {
        return SecurityConstants.IDENTITY_PATIENT.equals(identity)
                ? PATIENT_KEY_PREFIX + userId : STAFF_KEY_PREFIX + userId;
    }
}
