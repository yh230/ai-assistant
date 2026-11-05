package com.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * 安全密钥启动校验：生产环境禁止使用仓库内置默认 JWT 密钥与内部令牌
 * <p>
 * 开发环境保留默认值便于本地启动，但每次启动打印告警，避免默认值被带入生产。
 *
 * @author yh
 * @date 2026-11-05
 */
@Slf4j
@Component
public class SecuritySecretGuard {

    private static final String PLACEHOLDER = "please-override";

    private final Environment environment;
    private final String jwtSecret;
    private final String internalToken;

    public SecuritySecretGuard(Environment environment,
                               @Value("${security.jwt.secret:}") String jwtSecret,
                               @Value("${security.internal.token:}") String internalToken) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.internalToken = internalToken;
    }

    /**
     * 生产 profile 下使用默认/空密钥直接拒绝启动；非生产仅告警
     */
    @PostConstruct
    public void validate() {
        boolean weakJwt = !StringUtils.hasText(jwtSecret)
                || jwtSecret.contains(PLACEHOLDER)
                || jwtSecret.length() < 32;
        boolean weakInternal = !StringUtils.hasText(internalToken) || internalToken.contains(PLACEHOLDER);
        if (!weakJwt && !weakInternal) {
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException(
                    "生产环境必须通过环境变量提供强 JWT_SECRET（>=32 字节）与 INTERNAL_TOKEN，禁止使用默认值");
        }
        log.warn("检测到开发用默认安全密钥：JWT_SECRET/INTERNAL_TOKEN 未覆盖，仅限本地开发，生产启动将被拒绝");
    }
}
