package com.auth.sms;

import com.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

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

    /**
     * 原子校验脚本：命中即删码并清失败计数；未命中则自增并达阈值作废
     * KEYS[1]=验证码  KEYS[2]=失败计数  ARGV[1]=提交值  ARGV[2]=TTL 秒  ARGV[3]=失败阈值
     */
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>(
            "local stored = redis.call('GET', KEYS[1]) "
                    + "if stored == ARGV[1] then "
                    + "  redis.call('DEL', KEYS[1]); redis.call('DEL', KEYS[2]); return 1 "
                    + "end "
                    + "local fails = redis.call('INCR', KEYS[2]) "
                    + "if fails == 1 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end "
                    + "if fails >= tonumber(ARGV[3]) then redis.call('DEL', KEYS[1]) end "
                    + "return 0",
            Long.class);

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
        // SET NX EX 原子抢占冷却键，避免 hasKey+set 并发穿透
        Boolean firstSend = redis.opsForValue().setIfAbsent(cooldownKey, "1",
                Duration.ofSeconds(props.getSendCooldownSeconds()));
        if (!Boolean.TRUE.equals(firstSend)) {
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
        smsSender.send(phone, code);
    }

    /**
     * 校验验证码（Lua 原子消费：并发提交同一验证码只有一次能通过）
     *
     * @param identity 身份（staff/patient）
     * @param phone 手机号
     * @param code 用户提交的验证码
     */
    public void verify(String identity, String phone, String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("验证码错误或已过期");
        }
        String codeKey = "sms:code:" + identity + ":" + phone;
        String failKey = "sms:fail:" + identity + ":" + phone;
        String master = props.getMasterCode();
        boolean masterHit = props.consoleMode() && StringUtils.hasText(master) && constantTimeEquals(master, code);
        if (masterHit) {
            redis.delete(codeKey);
            redis.delete(failKey);
            return;
        }
        Long passed = redis.execute(VERIFY_SCRIPT, List.of(codeKey, failKey), code,
                String.valueOf(props.getCodeTtlSeconds()), String.valueOf(props.getMaxVerifyFailures()));
        if (Long.valueOf(1L).equals(passed)) {
            return;
        }
        throw new BusinessException("验证码错误或已过期");
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < props.getCodeLength(); i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
