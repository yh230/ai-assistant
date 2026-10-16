package com.auth.qr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 扫码登录配置（Nacos security.qr.* 下发）
 *
 * @author yh
 * @date 2026-10-16
 */
@Data
@ConfigurationProperties(prefix = "security.qr")
public class QrProperties {

    /** 场景有效期（秒） */
    private long ttlSeconds = 120;
}
