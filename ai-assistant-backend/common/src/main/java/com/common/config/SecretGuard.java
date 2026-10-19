package com.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 生产启动密钥守卫：prod profile 下拒绝默认/过短/缺失的关键密钥，开发环境仅告警
 *
 * <p>校验按应用类型区分：网关（spring.application.name=gateway）无 DataSource、无 api 模块，
 * 只需 JWT 与 CORS 白名单；其余服务需要 JWT、内部令牌，且仅当配置了数据源 URL 时才要求 DB 密码。</p>
 *
 * @author yh
 * @date 2026-10-19
 */
@Slf4j
@Component
public class SecretGuard implements InitializingBean {

    /** 生产 profile 名称 */
    private static final String PROD_PROFILE = "prod";

    /** 网关应用名，用于区分网关与业务服务 */
    private static final String GATEWAY_APP_NAME = "gateway";

    /** 代码中保留的开发默认值 */
    private static final List<String> DEV_DEFAULTS = List.of(
            "aidaima-dev-jwt-secret-please-override-0123456789",
            "aidaima-internal-token-please-override");

    /** 密钥最小长度 */
    private static final int MIN_SECRET_LENGTH = 32;

    /** 生产 DB 密码最小长度 */
    private static final int MIN_DB_PASSWORD_LENGTH = 12;

    /** 生产禁止的常见弱口令（比较前统一转小写） */
    private static final Set<String> WEAK_DB_PASSWORDS = Set.of("root", "123456", "password", "admin", "changeme");

    private final Environment environment;

    public SecretGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prod = isProdProfile();
        boolean gateway = GATEWAY_APP_NAME.equals(text("spring.application.name"));
        if (!prod) {
            if (environment.getActiveProfiles().length == 0 && isNonLocalDatasource()) {
                log.error("[SecretGuard] 未激活任何 profile 且 spring.datasource.url 指向非本地地址，"
                        + "密钥守卫已跳过（当前策略仅对 prod 强制生效），请确认部署 profile 配置");
            }
            boolean weakJwt = isWeak(text("security.jwt.secret"));
            boolean weakInternal = !gateway && isWeak(text("security.internal.token"));
            if (weakJwt || weakInternal) {
                log.warn("[SecretGuard] 正在使用开发默认/缺失的 JWT 或内部令牌，仅限本地开发，禁止用于生产");
            }
            return;
        }
        requireStrongSecret("security.jwt.secret", text("security.jwt.secret"));
        if (!gateway) {
            requireStrongSecret("security.internal.token", text("security.internal.token"));
            requireDatasourcePassword();
        }
        if ("console".equalsIgnoreCase(text("sms.provider")) || StringUtils.hasText(text("sms.master-code"))) {
            throw new IllegalStateException("[SecretGuard] 生产环境禁止 console 短信通道或万能验证码");
        }
        if (gateway) {
            requireCorsOrigins();
        }
    }

    /** 读取并去除首尾空白，避免带填充的默认值绕过比较 */
    private String text(String key) {
        String value = environment.getProperty(key);
        return value == null ? null : value.trim();
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (PROD_PROFILE.equals(profile == null ? null : profile.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 数据源 URL 是否有文本且不指向本地 */
    private boolean isNonLocalDatasource() {
        String url = text("spring.datasource.url");
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return !lower.contains("localhost") && !lower.contains("127.0.0.1");
    }

    private boolean isWeak(String value) {
        return !StringUtils.hasText(value)
                || value.length() < MIN_SECRET_LENGTH
                || DEV_DEFAULTS.contains(value);
    }

    private void requireStrongSecret(String key, String value) {
        if (isWeak(value)) {
            throw new IllegalStateException("[SecretGuard] 生产环境 " + key + " 缺失、过短或仍为默认值");
        }
    }

    /**
     * 仅在配置了数据源 URL 时校验 DB 密码（防御性检查，避免无数据源的应用被误伤）
     */
    private void requireDatasourcePassword() {
        String url = text("spring.datasource.url");
        if (!StringUtils.hasText(url)) {
            return;
        }
        String password = text("spring.datasource.password");
        if (!StringUtils.hasText(password)
                || password.length() < MIN_DB_PASSWORD_LENGTH
                || WEAK_DB_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("[SecretGuard] 生产环境 spring.datasource.password 缺失、少于 "
                    + MIN_DB_PASSWORD_LENGTH + " 位或为常见弱口令");
        }
    }

    /** 仅网关校验 CORS 白名单：不能为空、不能含 *、不能指向本地开发地址 */
    private void requireCorsOrigins() {
        String origins = text("security.cors.allowed-origins");
        if (!StringUtils.hasText(origins)) {
            throw new IllegalStateException("[SecretGuard] 生产环境网关必须配置 security.cors.allowed-origins");
        }
        int validOrigins = 0;
        for (String origin : origins.split(",")) {
            String candidate = origin.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.contains("*")) {
                throw new IllegalStateException("[SecretGuard] 生产环境 security.cors.allowed-origins 不能包含 *（发现 "
                        + candidate + "）");
            }
            if (isLocalOrigin(candidate)) {
                throw new IllegalStateException("[SecretGuard] 生产环境 security.cors.allowed-origins 禁止 localhost/127.0.0.1（发现 "
                        + candidate + "）");
            }
            validOrigins++;
        }
        if (validOrigins == 0) {
            throw new IllegalStateException("[SecretGuard] 生产环境 security.cors.allowed-origins 未包含有效来源");
        }
    }

    /** 判断 origin 的 host 是否为本地开发地址 */
    private boolean isLocalOrigin(String origin) {
        String value = origin.toLowerCase(Locale.ROOT);
        int schemeEnd = value.indexOf("://");
        String hostPort = schemeEnd >= 0 ? value.substring(schemeEnd + 3) : value;
        int slash = hostPort.indexOf('/');
        if (slash >= 0) {
            hostPort = hostPort.substring(0, slash);
        }
        String host;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            host = close >= 0 ? hostPort.substring(0, close + 1) : hostPort;
        } else {
            int colon = hostPort.indexOf(':');
            host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        }
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }
}
