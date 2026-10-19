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
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
@RequiredArgsConstructor
public class ScheduleLock {

    /** 排班锁前缀（+排班ID），与预约抢号共用，禁止改名 */
    private static final String SCHEDULE_LOCK_PREFIX = "lock:schedule:";
    /** 常规等锁时长（秒） */
    private static final long WAIT_SECONDS = 3;
    /** 锁租约（秒），显式租约避免看门狗续期不可控 */
    private static final long LEASE_SECONDS = 15;

    private final RedissonClient redissonClient;

    /**
     * 在排班锁保护下执行逻辑
     *
     * @param scheduleId 排班 ID
     * @param body 锁内逻辑
     * @param <T> 返回类型
     * @return 锁内逻辑返回值
     */
    public <T> T withLock(Long scheduleId, Supplier<T> body) {
        RLock lock = redissonClient.getLock(SCHEDULE_LOCK_PREFIX + scheduleId);
        boolean acquired;
        try {
            acquired = lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS);
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
