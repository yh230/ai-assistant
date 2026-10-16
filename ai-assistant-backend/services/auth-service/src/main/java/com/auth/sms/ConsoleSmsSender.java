package com.auth.sms;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sms.provider", havingValue = "console", matchIfMissing = true)
public class ConsoleSmsSender implements SmsSender {

    private final SmsProperties smsProperties;

    /**
     * 启动时对控制台降级模式告警（提示万能测试码被冒用登录的风险）
     */
    @PostConstruct
    public void warnConsoleMode() {
        log.warn("短信通道为控制台降级模式（provider=console）：验证码仅打印日志、不实际外发；"
                + "master-code（{}）处于生效状态，存在被用于登录的冒用风险，生产环境请接入真实通道并置空 master-code",
                smsProperties.getMasterCode());
    }

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
