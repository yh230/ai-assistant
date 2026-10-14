package com.common.security.operlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 操作日志参数脱敏与截断测试
 *
 * @author yh
 * @date 2026-10-14
 */
class OperLogSanitizerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldMaskSensitiveFields() {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("username", "admin");
        dto.put("newPassword", "secret-123");
        String json = OperLogSanitizer.argsToJson(new Object[]{dto}, objectMapper);
        assertTrue(json.contains("\"newPassword\":\"***\""));
        assertFalse(json.contains("secret-123"));
        assertTrue(json.contains("admin"));
    }

    @Test
    void shouldRecordOnlyFileNameForMultipart() {
        MockMultipartFile file = new MockMultipartFile("file", "guide.md", "text/markdown", "x".getBytes());
        String json = OperLogSanitizer.argsToJson(new Object[]{file}, objectMapper);
        assertTrue(json.contains("guide.md"));
    }

    @Test
    void shouldTruncateLongParams() {
        String json = OperLogSanitizer.argsToJson(new Object[]{"x".repeat(1500)}, objectMapper);
        assertEquals(1000, json.length());
    }

    @Test
    void shouldReturnEmptyForNullArgs() {
        assertEquals("", OperLogSanitizer.argsToJson(null, objectMapper));
        assertEquals("", OperLogSanitizer.argsToJson(new Object[0], objectMapper));
    }
}
