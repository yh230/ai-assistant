package com.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 生产启动密钥守卫：prod profile 下拒绝默认/过短/缺失的关键密钥，开发环境仅告警
 *
 * @author yh
 * @date 2026-10-19
 */
@Slf4j
@Component
public class SecretGuard implements InitializingBean {

    /** 代码中保留的开发默认值 */
    private static final List<String> DEV_DEFAULTS = List.of(
            "aidaima-dev-jwt-secret-please-override-0123456789",
            "aidaima-internal-token-please-override");

    /** 密钥最小长度 */
    private static final int MIN_SECRET_LENGTH = 32;

    private final Environment environment;

    public SecretGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        String jwt = environment.getProperty("security.jwt.secret");
        String internal = environment.getProperty("security.internal.token");
        if (!prod) {
            if (isWeak(jwt) || isWeak(internal)) {
                log.warn("[SecretGuard] 正在使用开发默认 JWT/内部令牌，仅限本地开发，禁止用于生产");
            }
            return;
        }
        requireStrong("security.jwt.secret", jwt);
        requireStrong("security.internal.token", internal);
        requireText("spring.datasource.password", environment.getProperty("spring.datasource.password"));
        if ("console".equalsIgnoreCase(environment.getProperty("sms.provider"))
                || StringUtils.hasText(environment.getProperty("sms.master-code"))) {
            throw new IllegalStateException("[SecretGuard] 生产环境禁止 console 短信通道或万能验证码");
        }
        String origins = environment.getProperty("security.cors.allowed-origins");
        if (!StringUtils.hasText(origins) || origins.contains("*")) {
            throw new IllegalStateException("[SecretGuard] 生产环境 security.cors.allowed-origins 必须配置且不能包含 *");
        }
    }

    private boolean isWeak(String value) {
        return !StringUtils.hasText(value)
                || value.length() < MIN_SECRET_LENGTH
                || DEV_DEFAULTS.contains(value);
    }

    private void requireStrong(String key, String value) {
        if (isWeak(value)) {
            throw new IllegalStateException("[SecretGuard] 生产环境 " + key + " 缺失、过短或仍为默认值");
        }
    }

    private void requireText(String key, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("[SecretGuard] 生产环境 " + key + " 不能为空");
        }
    }
}
