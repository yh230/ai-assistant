package com.auth.sms;

import com.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 验证码服务：生成/存储/校验/节流
 * <p>
 * 键 sms:code:{identity}:{phone} 存验证码；sms:fail:{identity}:{phone} 计错误次数；
 * sms:send:{identity}:{phone} 冷却；sms:daily:{identity}:{phone} 日计数
 *
 * @author yh
 * @date 2026-10-16
 */
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SmsSender smsSender;
    private final SmsProperties props;

    /**
     * 发送验证码（节流：冷却 + 日上限）
     *
     * @param identity 身份（staff/patient）
     * @param phone 手机号
     */
    public void sendCode(String identity, String phone) {
        String cooldownKey = "sms:send:" + identity + ":" + phone;
        if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) {
            throw new BusinessException("发送过于频繁，请稍后再试");
        }
        String dailyKey = "sms:daily:" + identity + ":" + phone;
        Long daily = redis.opsForValue().increment(dailyKey);
        if (daily != null && daily == 1L) {
            redis.expire(dailyKey, Duration.ofDays(1));
        }
        if (daily != null && daily > props.getDailyLimit()) {
            throw new BusinessException("今日验证码发送次数已达上限");
        }
        String code = generateCode();
        redis.opsForValue().set("sms:code:" + identity + ":" + phone, code,
                Duration.ofSeconds(props.getCodeTtlSeconds()));
        redis.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(props.getSendCooldownSeconds()));
        smsSender.send(phone, code);
    }

    /**
     * 校验验证码（成功消费并清错误计数；失败计数，达阈值作废该码）
     *
     * @param identity 身份（staff/patient）
     * @param phone 手机号
     * @param code 用户提交的验证码
     */
    public void verify(String identity, String phone, String code) {
        String codeKey = "sms:code:" + identity + ":" + phone;
        String stored = redis.opsForValue().get(codeKey);
        String master = props.getMasterCode();
        boolean masterHit = props.consoleMode() && StringUtils.hasText(master) && master.equals(code);
        if (masterHit || (stored != null && stored.equals(code))) {
            redis.delete(codeKey);
            redis.delete("sms:fail:" + identity + ":" + phone);
            return;
        }
        String failKey = "sms:fail:" + identity + ":" + phone;
        Long fails = redis.opsForValue().increment(failKey);
        if (fails != null && fails == 1L) {
            redis.expire(failKey, Duration.ofSeconds(props.getCodeTtlSeconds()));
        }
        if (fails != null && fails >= props.getMaxVerifyFailures()) {
            redis.delete(codeKey);
        }
        throw new BusinessException("验证码错误或已过期");
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < props.getCodeLength(); i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
