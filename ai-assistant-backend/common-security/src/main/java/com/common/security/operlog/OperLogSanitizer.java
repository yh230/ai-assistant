package com.common.security.operlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 操作日志参数处理：跳过容器对象、文件只记文件名、敏感字段脱敏、超长截断
 *
 * @author yh
 * @date 2026-10-14
 */
public final class OperLogSanitizer {

    /** 参数留痕最大长度 */
    public static final int PARAMS_MAX_LENGTH = 1000;

    /** 敏感字段名正则（匹配到的值替换为 ***） */
    private static final Pattern SENSITIVE = Pattern.compile(
            "\"(password|oldPassword|newPassword|pwd|idCard|patientIdCard)\"\\s*:\\s*\"[^\"]*\"");

    private OperLogSanitizer() {
    }

    /**
     * 将方法参数序列化为审计留痕 JSON（脱敏+截断）
     *
     * @param args 方法参数数组
     * @param objectMapper JSON 序列化器
     * @return 处理后的参数 JSON（无参数为空串）
     */
    public static String argsToJson(Object[] args, ObjectMapper objectMapper) {
        if (args == null || args.length == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object arg : args) {
            if (arg == null || arg instanceof ServletRequest || arg instanceof ServletResponse) {
                continue;
            }
            if (arg instanceof MultipartFile file) {
                parts.add("\"file\":\"" + file.getOriginalFilename() + "\"");
                continue;
            }
            try {
                parts.add(objectMapper.writeValueAsString(arg));
            } catch (Exception e) {
                parts.add(String.valueOf(arg));
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return truncate(maskSensitive("[" + String.join(",", parts) + "]"), PARAMS_MAX_LENGTH);
    }

    /**
     * 按字段名脱敏敏感值（密码/身份证）
     *
     * @param json JSON 串
     * @return 脱敏后的 JSON 串
     */
    public static String maskSensitive(String json) {
        return json == null ? "" : SENSITIVE.matcher(json).replaceAll("\"$1\":\"***\"");
    }

    /**
     * 超长截断
     *
     * @param text 原文本
     * @param max 最大长度
     * @return 截断后的文本
     */
    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
