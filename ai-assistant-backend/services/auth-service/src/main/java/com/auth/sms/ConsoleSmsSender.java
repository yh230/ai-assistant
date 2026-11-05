package com.auth.sms;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

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
    private final Environment environment;

    /**
     * 控制台通道禁止用于生产；启动时提示万能码是否生效（不打印具体值）
     */
    @PostConstruct
    public void warnConsoleMode() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("生产环境禁止使用 sms.provider=console，请接入真实短信通道");
        }
        log.warn("短信通道为控制台降级模式（provider=console）：验证码仅打印日志、不实际外发；"
                        + "master-code {}，存在被用于登录的冒用风险，生产环境请接入真实通道并置空 master-code",
                StringUtils.hasText(smsProperties.getMasterCode()) ? "已配置" : "未配置");
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
