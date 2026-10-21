package com.mcp.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * API Key 哈希与常量时间比较
 *
 * @author yh
 * @date 2026-10-21
 */
@Component
public class McpKeyHasher {

    /**
     * 计算 SHA-256 十六进制值
     *
     * @param raw 原始 Key
     * @return 小写十六进制哈希
     */
    public String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 校验原始 Key 是否匹配配置的哈希
     *
     * @param raw 原始 Key
     * @param expectedHex 配置的哈希
     * @return 是否匹配
     */
    public boolean matches(String raw, String expectedHex) {
        if (raw == null || raw.isBlank() || expectedHex == null || expectedHex.isBlank()) {
            return false;
        }
        byte[] actual = sha256(raw).getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedHex.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expected);
    }
}
