package com.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 生产密钥守卫测试：prod 拒绝默认/过短/不安全配置，dev 允许默认值
 *
 * @author yh
 * @date 2026-10-19
 */
class SecretGuardTest {

    private MockEnvironment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("security.jwt.secret", "j".repeat(48));
        env.setProperty("security.internal.token", "i".repeat(48));
        env.setProperty("spring.datasource.password", "db-pass");
        env.setProperty("security.cors.allowed-origins", "https://admin.example.com");
        return env;
    }

    @Test
    void prodRejectsDefaultJwt() {
        MockEnvironment env = prodEnv();
        env.setProperty("security.jwt.secret", "aidaima-dev-jwt-secret-please-override-0123456789");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodRejectsShortInternalToken() {
        MockEnvironment env = prodEnv();
        env.setProperty("security.internal.token", "short");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodRejectsConsoleSms() {
        MockEnvironment env = prodEnv();
        env.setProperty("sms.provider", "console");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodRejectsWildcardCors() {
        MockEnvironment env = prodEnv();
        env.setProperty("security.cors.allowed-origins", "*");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodAcceptsStrongConfig() {
        assertDoesNotThrow(() -> new SecretGuard(prodEnv()).afterPropertiesSet());
    }

    @Test
    void devAllowsDefaults() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("security.jwt.secret", "aidaima-dev-jwt-secret-please-override-0123456789");
        env.setProperty("security.internal.token", "aidaima-internal-token-please-override");
        assertDoesNotThrow(() -> new SecretGuard(env).afterPropertiesSet());
    }
}
