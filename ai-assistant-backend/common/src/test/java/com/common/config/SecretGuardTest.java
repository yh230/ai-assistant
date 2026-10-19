package com.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 生产密钥守卫测试：prod 拒绝默认/过短/不安全配置，dev 允许默认值；
 * 网关与服务按应用类型分别校验。
 *
 * @author yh
 * @date 2026-10-19
 */
class SecretGuardTest {

    private static final String STRONG_JWT = "j".repeat(48);
    private static final String STRONG_INTERNAL_TOKEN = "i".repeat(48);
    private static final String STRONG_DB_PASSWORD = "db-strong-pass-0123";
    private static final String PROD_CORS = "https://admin.example.com";

    /** 业务服务上的生产环境：有数据源、需要内部令牌，不需要 CORS 白名单 */
    private MockEnvironment serviceEnv() {
        return serviceEnv(STRONG_DB_PASSWORD);
    }

    private MockEnvironment serviceEnv(String dbPassword) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.application.name", "auth-service");
        env.setProperty("security.jwt.secret", STRONG_JWT);
        env.setProperty("security.internal.token", STRONG_INTERNAL_TOKEN);
        env.setProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/aidaima");
        if (dbPassword != null) {
            env.setProperty("spring.datasource.password", dbPassword);
        }
        return env;
    }

    /** 网关上的生产环境：无数据源、无内部令牌，需要 JWT 与 CORS 白名单 */
    private MockEnvironment gatewayEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.application.name", "gateway");
        env.setProperty("security.jwt.secret", STRONG_JWT);
        env.setProperty("security.cors.allowed-origins", PROD_CORS);
        return env;
    }

    @Test
    void prodRejectsDefaultJwt() {
        MockEnvironment env = serviceEnv();
        env.setProperty("security.jwt.secret", "aidaima-dev-jwt-secret-please-override-0123456789");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodRejectsPaddedDefaultJwt() {
        MockEnvironment env = serviceEnv();
        env.setProperty("security.jwt.secret", "  aidaima-dev-jwt-secret-please-override-0123456789  ");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodRejectsShortInternalToken() {
        MockEnvironment env = serviceEnv();
        env.setProperty("security.internal.token", "short");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodRejectsConsoleSms() {
        MockEnvironment env = serviceEnv();
        env.setProperty("sms.provider", "console");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodRejectsWildcardCors() {
        MockEnvironment env = gatewayEnv();
        env.setProperty("security.cors.allowed-origins", "*");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodGatewayRejectsWildcardSubdomainCors() {
        MockEnvironment env = gatewayEnv();
        env.setProperty("security.cors.allowed-origins", "https://*.example.com");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodGatewayRejectsLocalhostCors() {
        MockEnvironment env = gatewayEnv();
        env.setProperty("security.cors.allowed-origins", "http://localhost:5173");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodGatewayRejectsLoopbackIpCors() {
        MockEnvironment env = gatewayEnv();
        env.setProperty("security.cors.allowed-origins", "http://127.0.0.1:5173");
        assertThrows(IllegalStateException.class, () -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void prodAcceptsStrongServiceConfig() {
        assertDoesNotThrow(() -> new SecretGuard(serviceEnv()).afterPropertiesSet());
    }

    @Test
    void prodAcceptsGatewayWithoutDatasourceOrInternalToken() {
        assertDoesNotThrow(() -> new SecretGuard(gatewayEnv()).afterPropertiesSet());
    }

    @Test
    void serviceProdRejectsMissingDbPassword() {
        assertThrows(IllegalStateException.class, () -> new SecretGuard(serviceEnv(null)).afterPropertiesSet());
    }

    @Test
    void serviceProdRejectsWeakDbPassword() {
        assertThrows(IllegalStateException.class, () -> new SecretGuard(serviceEnv("root")).afterPropertiesSet());
    }

    @Test
    void serviceProdRejectsShortDbPassword() {
        assertThrows(IllegalStateException.class, () -> new SecretGuard(serviceEnv("short-pass")).afterPropertiesSet());
    }

    @Test
    void serviceWithoutDatasourceDoesNotRequireDbPassword() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.application.name", "auth-service");
        env.setProperty("security.jwt.secret", STRONG_JWT);
        env.setProperty("security.internal.token", STRONG_INTERNAL_TOKEN);
        assertDoesNotThrow(() -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void devAllowsDefaults() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("security.jwt.secret", "aidaima-dev-jwt-secret-please-override-0123456789");
        env.setProperty("security.internal.token", "aidaima-internal-token-please-override");
        assertDoesNotThrow(() -> new SecretGuard(env).afterPropertiesSet());
    }

    @Test
    void noActiveProfileWithNonLocalDatasourceDoesNotFail() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "auth-service");
        env.setProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/aidaima");
        env.setProperty("security.jwt.secret", "aidaima-dev-jwt-secret-please-override-0123456789");
        assertDoesNotThrow(() -> new SecretGuard(env).afterPropertiesSet());
    }
}
