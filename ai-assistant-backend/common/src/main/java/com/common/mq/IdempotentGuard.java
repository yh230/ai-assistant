package com.common.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 消费幂等守卫：按消费组+事件ID 的 Redis SETNX 去重
 * <p>
 * Redis 异常时返回 true（放行）以避免消息丢失——重复消费由业务侧容忍（通知/审计重复优于丢失），
 * 向量化侧以文档状态幂等兜底
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentGuard {

    /** 幂等键前缀 */
    private static final String KEY_PREFIX = "mq:idem:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试消费一次（首次返回 true，重复返回 false）
     *
     * @param consumerGroup 消费组
     * @param eventId 事件唯一ID
     * @param ttl 幂等键保留时长
     * @return true=首次消费；false=重复消息（应直接 ack 丢弃）
     */
    public boolean consumeOnce(String consumerGroup, String eventId, Duration ttl) {
        if (!StringUtils.hasText(eventId)) {
            return true;
        }
        try {
            Boolean first = stringRedisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + consumerGroup + ":" + eventId, "1", ttl);
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.warn("幂等键写入失败，放行消费以防消息丢失: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 失败回退幂等键（处理异常时调用，使重试可重放同一事件）
     *
     * @param consumerGroup 消费组
     * @param eventId 事件唯一ID
     */
    public void release(String consumerGroup, String eventId) {
        if (!StringUtils.hasText(eventId)) {
            return;
        }
        try {
            stringRedisTemplate.delete(KEY_PREFIX + consumerGroup + ":" + eventId);
        } catch (Exception e) {
            log.warn("幂等键释放失败: {}", e.getMessage());
        }
    }
}
