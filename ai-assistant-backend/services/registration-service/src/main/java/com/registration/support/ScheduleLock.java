package com.registration.support;

import com.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 排班级分布式锁：号源再生与预约抢号共用同一把锁
 * <p>Redisson RLock 按线程可重入，update 持锁后调用号源再生（同键）是安全的。</p>
 * <p>锁不显式指定租期，交由 Redisson 看门狗自动续期，避免长事务/Feign 调用超过租期被提前释放；
 * 调用方须保证事务在锁内提交（TransactionTemplate），即“锁必须覆盖事务提交”。</p>
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
@RequiredArgsConstructor
public class ScheduleLock {

    /** 排班锁前缀（+排班ID），与预约抢号共用，禁止改名 */
    public static final String SCHEDULE_LOCK_PREFIX = "lock:schedule:";
    /** 排班号源槽锁前缀（+医生ID+日期+时段），防同医生同日期时段并发建排班 */
    public static final String SCHEDULE_SLOT_LOCK_PREFIX = "lock:schedule-slot:";
    /** 常规等锁时长（秒） */
    private static final long WAIT_SECONDS = 3;

    private final RedissonClient redissonClient;

    /**
     * 在排班锁（lock:schedule:{scheduleId}）保护下执行逻辑
     *
     * @param scheduleId 排班 ID
     * @param body 锁内逻辑
     * @param <T> 返回类型
     * @return 锁内逻辑返回值
     */
    public <T> T withLock(Long scheduleId, Supplier<T> body) {
        return withLock(SCHEDULE_LOCK_PREFIX + scheduleId, body);
    }

    /**
     * 在指定锁键保护下执行逻辑
     *
     * @param key 完整锁键
     * @param body 锁内逻辑
     * @param <T> 返回类型
     * @return 锁内逻辑返回值
     */
    public <T> T withLock(String key, Supplier<T> body) {
        RLock lock = redissonClient.getLock(key);
        boolean acquired;
        try {
            // 仅指定等待时长，不指定租期：启用 Redisson 看门狗自动续期（长事务/Feign 循环不会被提前释放）
            acquired = lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("当前操作繁忙，请稍后重试");
        }
        if (!acquired) {
            throw new BusinessException("当前操作繁忙，请稍后重试");
        }
        try {
            return body.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
