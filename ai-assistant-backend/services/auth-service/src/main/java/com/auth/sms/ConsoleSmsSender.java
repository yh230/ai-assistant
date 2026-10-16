package com.auth.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 控制台短信发送器（开发期降级：验证码打日志）
 *
 * @author yh
 * @date 2026-10-16
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "console", matchIfMissing = true)
public class ConsoleSmsSender implements SmsSender {

    /**
     * 将验证码打印到日志（不实际外发）
     *
     * @param phone 手机号
     * @param code 验证码
     */
    @Override
    public void send(String phone, String code) {
        log.info("【短信降级】验证码发送 phone={}, code={}", phone, code);
    }
}
