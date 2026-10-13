package com.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户权限快照存取（Redis key: auth:user:{userId}，TTL 30 分钟）
 *
 * @author yh
 * @date 2026-10-12
 */
@Component
@RequiredArgsConstructor
public class UserSnapshotStore {

    private static final String KEY_PREFIX = "auth:user:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 保存快照（覆盖写并续期） */
    public void save(UserSnapshot snapshot) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(KEY_PREFIX + snapshot.getUserId(), objectMapper.writeValueAsString(snapshot), TTL);
        } catch (Exception e) {
            throw new IllegalStateException("序列化用户快照失败: userId=" + snapshot.getUserId(), e);
        }
    }

    /** 读取快照（不存在返回 null） */
    public UserSnapshot get(Long userId) {
        String json = stringRedisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, UserSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化用户快照失败: userId=" + userId, e);
        }
    }

    /** 失效快照 */
    public void evict(Long userId) {
        stringRedisTemplate.delete(KEY_PREFIX + userId);
    }
}
