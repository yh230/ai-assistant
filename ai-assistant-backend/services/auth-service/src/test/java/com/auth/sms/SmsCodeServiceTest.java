package com.auth.sms;

import com.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 短信验证码服务测试（冷却节流 / 万能码 / 错误计数）
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
        when(redis.hasKey(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.sendCode("staff", "13800000000"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 万能码在console模式校验通过() {
        when(ops.get("sms:code:staff:13800000000")).thenReturn("888888");
        service.verify("staff", "13800000000", "123456");
        verify(redis).delete("sms:code:staff:13800000000");
    }

    @Test
    void 错误验证码抛错并计数() {
        when(ops.get("sms:code:staff:13800000000")).thenReturn("888888");
        when(ops.increment("sms:fail:staff:13800000000")).thenReturn(1L);
        assertThatThrownBy(() -> service.verify("staff", "13800000000", "000000"))
                .isInstanceOf(BusinessException.class);
    }
}
