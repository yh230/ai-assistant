package com.auth.sms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短信验证码配置（Nacos sms.* 下发）
 *
 * @author yh
 * @date 2026-10-16
 */
@Data
@ConfigurationProperties(prefix = "sms")
public class SmsProperties {

    /** 短信通道实现：console=控制台降级 */
    private String provider = "console";

    /** 万能测试码（仅 provider=console 生效；生产置空） */
    private String masterCode = "";

    /** 验证码位数 */
    private int codeLength = 6;

    /** 验证码有效期（秒） */
    private long codeTtlSeconds = 300;

    /** 同号发送冷却（秒） */
    private long sendCooldownSeconds = 60;

    /** 单号每日上限 */
    private int dailyLimit = 10;

    /** 单码最大校验失败次数 */
    private int maxVerifyFailures = 5;

    /**
     * 是否 console 降级模式
     *
     * @return 通道为 console 时返回 true
     */
    public boolean consoleMode() {
        return "console".equalsIgnoreCase(provider);
    }
}
