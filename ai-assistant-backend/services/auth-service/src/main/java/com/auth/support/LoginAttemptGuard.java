package com.auth.support;

import com.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 登录失败锁定守卫：Redis 记录连续失败次数，达到阈值后按账号锁定
 * <p>
 * 键按身份隔离（员工/患者 ID 空间重叠）：login:fail:{identity}:{username} 与 login:lock:{identity}:{username}；
 * 计数在窗口期内累计，登录成功后清零
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
public class LoginAttemptGuard {

    private final StringRedisTemplate stringRedisTemplate;
    private final int maxFailures;
    private final long windowMinutes;
    private final long lockMinutes;

    /**
     * 构造守卫（阈值/窗口/锁定时长可配置）
     *
     * @param stringRedisTemplate Redis 客户端
     * @param maxFailures 触发锁定的连续失败次数
     * @param windowMinutes 失败计数窗口（分钟）
     * @param lockMinutes 锁定时长（分钟）
     */
    public LoginAttemptGuard(StringRedisTemplate stringRedisTemplate,
                             @Value("${security.login.max-failures:5}") int maxFailures,
                             @Value("${security.login.window-minutes:15}") long windowMinutes,
                             @Value("${security.login.lock-minutes:15}") long lockMinutes) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.maxFailures = maxFailures;
        this.windowMinutes = windowMinutes;
        this.lockMinutes = lockMinutes;
    }

    /**
     * 校验账号是否处于锁定状态
     *
     * @param identity 身份（staff/patient）
     * @param username 登录账号（患者为手机号）
     * @throws BusinessException 账号已锁定时抛出
     */
    public void assertNotLocked(String identity, String username) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey(identity, username)))) {
            throw new BusinessException("登录失败次数过多，账号已锁定，请稍后再试");
        }
    }

    /**
     * 记录一次登录失败，达到阈值时置锁定键
     *
     * @param identity 身份（staff/patient）
     * @param username 登录账号
     */
    public void recordFailure(String identity, String username) {
        String key = failKey(identity, username);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofMinutes(windowMinutes));
        }
        if (count != null && count >= maxFailures) {
            stringRedisTemplate.opsForValue().set(lockKey(identity, username), "1", Duration.ofMinutes(lockMinutes));
            log.warn("账号连续登录失败 {} 次，已锁定 {} 分钟: {}:{}", count, lockMinutes, identity, username);
        }
    }

    /**
     * 登录成功后清空失败计数与锁定标记
     *
     * @param identity 身份（staff/patient）
     * @param username 登录账号
     */
    public void reset(String identity, String username) {
        stringRedisTemplate.delete(List.of(failKey(identity, username), lockKey(identity, username)));
    }

    private String failKey(String identity, String username) {
        return "login:fail:" + identity + ":" + username;
    }

    private String lockKey(String identity, String username) {
        return "login:lock:" + identity + ":" + username;
    }
}
