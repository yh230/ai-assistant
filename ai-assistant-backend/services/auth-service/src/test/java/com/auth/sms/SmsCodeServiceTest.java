package com.auth.sms;

import com.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 短信验证码服务测试（冷却节流 / 万能码 / 原子校验）
 *
 * @author yh
 * @date 2026-10-16
 */
class SmsCodeServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private SmsSender sender;
    private SmsCodeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        sender = mock(SmsSender.class);
        when(redis.opsForValue()).thenReturn(ops);
        SmsProperties props = new SmsProperties();
        props.setProvider("console");
        props.setMasterCode("123456");
        props.setCodeLength(6);
        props.setCodeTtlSeconds(300);
        props.setSendCooldownSeconds(60);
        service = new SmsCodeService(redis, sender, props);
    }

    @Test
    void 冷却期内重复发送被拒() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> service.sendCode("staff", "13800000000"))
                .isInstanceOf(BusinessException.class);
        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    void 首次发送写入验证码并外发() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(ops.increment("sms:daily:staff:13800000000")).thenReturn(1L);

        service.sendCode("staff", "13800000000");

        verify(ops).set(eq("sms:code:staff:13800000000"), anyString(), eq(Duration.ofSeconds(300)));
        verify(sender).send(eq("13800000000"), anyString());
    }

    @Test
    void 万能码在console模式校验通过() {
        service.verify("staff", "13800000000", "123456");
        verify(redis).delete("sms:code:staff:13800000000");
        verify(redis).delete("sms:fail:staff:13800000000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 原子校验通过时不再抛错() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);

        service.verify("staff", "13800000000", "888888");

        verify(redis).execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 错误验证码抛错并计数() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(0L);
        assertThatThrownBy(() -> service.verify("staff", "13800000000", "000000"))
                .isInstanceOf(BusinessException.class);
    }
}
