package com.registration.support;

import com.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排班锁组件单测（锁键、看门狗等锁签名、等锁失败快速抛出、异常路径释放）
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class ScheduleLockTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private ScheduleLock scheduleLock;

    @Test
    void executesBodyAndUnlocksOnSuccess() throws InterruptedException {
        when(redissonClient.getLock("lock:schedule:7")).thenReturn(lock);
        // 看门狗模式：仅传等待时长，不传租期
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = scheduleLock.withLock(7L, () -> "ok");

        assertEquals("ok", result);
        verify(redissonClient).getLock("lock:schedule:7");
        verify(lock).tryLock(3, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    void executesBodyWithExplicitLockKey() throws InterruptedException {
        String slotKey = ScheduleLock.SCHEDULE_SLOT_LOCK_PREFIX + "200:2026-10-20:1";
        when(redissonClient.getLock(slotKey)).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = scheduleLock.withLock(slotKey, () -> "slot-ok");

        assertEquals("slot-ok", result);
        verify(redissonClient).getLock("lock:schedule-slot:200:2026-10-20:1");
        verify(lock).tryLock(3, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    void throwsBusyWhenLockNotAcquired() throws InterruptedException {
        when(redissonClient.getLock("lock:schedule:7")).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
        AtomicBoolean executed = new AtomicBoolean(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scheduleLock.withLock(7L, () -> {
                    executed.set(true);
                    return null;
                }));

        assertEquals("当前操作繁忙，请稍后重试", ex.getMessage());
        assertFalse(executed.get(), "未拿到锁时不得执行锁内逻辑");
        verify(lock, never()).isHeldByCurrentThread();
        verify(lock, never()).unlock();
    }

    @Test
    void unlocksWhenBodyThrows() throws InterruptedException {
        when(redissonClient.getLock("lock:schedule:8")).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> scheduleLock.withLock(8L, () -> {
                    throw new IllegalStateException("boom");
                }));

        verify(lock).unlock();
    }

    @Test
    void restoresInterruptFlagWhenInterruptedWhileWaiting() throws InterruptedException {
        when(redissonClient.getLock("lock:schedule:9")).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("interrupted"));

        try {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> scheduleLock.withLock(9L, () -> null));
            assertEquals("当前操作繁忙，请稍后重试", ex.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "中断标记必须保留");
        } finally {
            Thread.interrupted(); // 清理标记，避免污染其他用例
        }
        verify(lock, never()).unlock();
    }

    @Test
    void supportsNestedAcquireForUpdateThenRegenerate() throws InterruptedException {
        // update 持锁后调用号源再生（同键）：Redisson RLock 线程可重入，两次调用均应成功
        when(redissonClient.getLock("lock:schedule:10")).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = scheduleLock.withLock(10L,
                () -> scheduleLock.withLock(10L, () -> "done"));

        assertEquals("done", result);
        verify(lock, times(2)).tryLock(3, TimeUnit.SECONDS);
        verify(lock, times(2)).unlock();
    }
}
