package com.auth.support;

import com.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.net.UnknownHostException;
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

    private final MeterRegistry meterRegistry;
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
     * @param meterRegistry 指标注册表
     */
    public LoginAttemptGuard(StringRedisTemplate stringRedisTemplate,
                             @Value("${security.login.max-failures:5}") int maxFailures,
                             @Value("${security.login.window-minutes:15}") long windowMinutes,
                             @Value("${security.login.lock-minutes:15}") long lockMinutes,
                             MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.maxFailures = maxFailures;
        this.windowMinutes = windowMinutes;
        this.lockMinutes = lockMinutes;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 校验账号是否处于锁定状态（同时检查来源 IP 锁定）
     *
     * @param identity 身份（staff/patient）
     * @param username 登录账号（患者为手机号）
     * @throws BusinessException 账号或来源 IP 已锁定时抛出
     */
    public void assertNotLocked(String identity, String username) {
        String ip = currentIp();
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey(identity, username)))
                || (ip != null && Boolean.TRUE.equals(stringRedisTemplate.hasKey(ipLockKey(identity, ip))))) {
            throw new BusinessException("登录失败次数过多，账号已锁定，请稍后再试");
        }
    }

    /**
     * 记录一次登录失败：单 IP 达阈值只锁该 IP，多 IP 累计达阈值才锁账号，避免被单点恶意锁死账号
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
        String ip = currentIp();
        boolean singleIpLocked = false;
        if (ip != null) {
            String ipKey = ipFailKey(identity, ip);
            Long ipCount = stringRedisTemplate.opsForValue().increment(ipKey);
            if (ipCount != null && ipCount == 1L) {
                stringRedisTemplate.expire(ipKey, Duration.ofMinutes(windowMinutes));
            }
            String ipsKey = failIpSetKey(identity, username);
            stringRedisTemplate.opsForSet().add(ipsKey, ip);
            stringRedisTemplate.expire(ipsKey, Duration.ofMinutes(windowMinutes));
            // 单 IP 阈值高于账号阈值，减少 NAT 共享出口的误锁
            if (ipCount != null && ipCount >= maxFailures * 3L) {
                stringRedisTemplate.opsForValue().set(ipLockKey(identity, ip), "1", Duration.ofMinutes(5));
                singleIpLocked = true;
            }
        }
        if (count != null && count >= maxFailures) {
            Long distinctIps = ip == null ? null : stringRedisTemplate.opsForSet().size(failIpSetKey(identity, username));
            if (ip == null || distinctIps == null || distinctIps >= 2) {
                stringRedisTemplate.opsForValue().set(lockKey(identity, username), "1", Duration.ofMinutes(lockMinutes));
                log.warn("账号连续登录失败 {} 次，已锁定 {} 分钟: {}:{}", count, lockMinutes, identity, username);
            } else if (singleIpLocked) {
                log.warn("来源 IP 连续登录失败，已锁定该 IP 5 分钟: {}:{}", identity, ip);
            }
        }
        meterRegistry.counter("ai.login.total", "identity", identity, "result", "failure").increment();
    }

    /**
     * 登录成功后清空账号失败计数与锁定标记（来源 IP 计数一并清理）
     *
     * @param identity 身份（staff/patient）
     * @param username 登录账号
     */
    public void reset(String identity, String username) {
        String ip = currentIp();
        if (ip != null) {
            stringRedisTemplate.delete(List.of(ipFailKey(identity, ip), ipLockKey(identity, ip)));
            stringRedisTemplate.opsForSet().remove(failIpSetKey(identity, username), ip);
        }
        stringRedisTemplate.delete(List.of(failKey(identity, username), lockKey(identity, username)));
        meterRegistry.counter("ai.login.total", "identity", identity, "result", "success").increment();
    }

    /** 当前请求来源 IP（取 X-Forwarded-For 最后一段，即最近可信代理追加的地址；无 XFF 时仅信任公网直连地址） */
    private static String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            String[] parts = forwarded.split(",");
            String last = parts[parts.length - 1].trim();
            if (StringUtils.hasText(last) && !"unknown".equalsIgnoreCase(last)) {
                return last;
            }
        }
        String remote = request.getRemoteAddr();
        // 私网/回环地址通常是网关自身，按它锁定会误锁全部用户
        return isPrivateOrLoopback(remote) ? null : remote;
    }

    /**
     * 判断是否为私网/回环/链路本地地址
     *
     * @param ip 待判断地址
     * @return 私网或无法解析时返回 true
     */
    private static boolean isPrivateOrLoopback(String ip) {
        if (!StringUtils.hasText(ip)) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private String failKey(String identity, String username) {
        return "login:fail:" + identity + ":" + username;
    }

    private String lockKey(String identity, String username) {
        return "login:lock:" + identity + ":" + username;
    }

    private String ipFailKey(String identity, String ip) {
        return "login:fail:ip:" + identity + ":" + ip;
    }

    private String ipLockKey(String identity, String ip) {
        return "login:lock:ip:" + identity + ":" + ip;
    }

    private String failIpSetKey(String identity, String username) {
        return "login:ips:" + identity + ":" + username;
    }
}
