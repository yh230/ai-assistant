package com.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关跨域白名单测试
 *
 * @author yh
 * @date 2026-10-19
 */
class CorsConfigTest {

    @Test
    void usesExactOriginsWithoutWildcard() {
        CorsConfiguration config = CorsConfig.buildConfig("https://admin.example.com,https://h5.example.com");
        assertEquals(java.util.List.of("https://admin.example.com", "https://h5.example.com"),
                config.getAllowedOrigins());
        assertEquals(Boolean.TRUE, config.getAllowCredentials());
        assertFalse(config.getAllowedOrigins().contains("*"));
    }

    @Test
    void trimsWhitespaceAndSkipsBlankEntries() {
        CorsConfiguration config = CorsConfig.buildConfig(" https://admin.example.com , ,https://h5.example.com ");
        assertEquals(java.util.List.of("https://admin.example.com", "https://h5.example.com"),
                config.getAllowedOrigins());
    }

    @Test
    void neverUsesAllowedOriginPatterns() {
        CorsConfiguration config = CorsConfig.buildConfig("https://admin.example.com");
        assertTrue(config.getAllowedOriginPatterns() == null || config.getAllowedOriginPatterns().isEmpty());
    }

    @Test
    void rejectsWildcardOrigin() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> CorsConfig.buildConfig("*"));
        assertTrue(ex.getMessage().contains("wildcard"));
    }

    @Test
    void rejectsWildcardAmongExactOrigins() {
        assertThrows(IllegalStateException.class,
                () -> CorsConfig.buildConfig("https://admin.example.com,*"));
    }

    @Test
    void rejectsBlankOrigins() {
        assertThrows(IllegalStateException.class, () -> CorsConfig.buildConfig("   "));
        assertThrows(IllegalStateException.class, () -> CorsConfig.buildConfig(" , , "));
    }

    @Test
    void rejectsNullOrigins() {
        assertThrows(IllegalStateException.class, () -> CorsConfig.buildConfig(null));
    }
}
