package com.aiassistant.service.support;

import com.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天链路文本工具：分片、JSON 序列化与输入长度校验，供员工侧/患者侧共用。
 *
 * @author yh
 * @date 2026-10-27
 */
public final class ChatTextSupport {

    private static final Logger log = LoggerFactory.getLogger(ChatTextSupport.class);

    private ChatTextSupport() {
    }

    /**
     * 将文本按固定长度切片。
     *
     * @param text      原始文本
     * @param chunkSize 每片字符数
     * @return 切片列表；文本为空时返回空列表
     */
    public static List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty() || chunkSize <= 0) {
            return chunks;
        }
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            chunks.add(text.substring(i, end));
        }
        return chunks;
    }

    /**
     * 将对象序列化为 JSON 字符串（失败返回空对象）。
     *
     * @param objectMapper JSON 序列化器
     * @param value        待序列化对象
     * @return JSON 字符串
     */
    public static String toJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 校验用户消息长度（服务层兜底）。
     *
     * @param maxChars 最大字符数
     * @param message  用户消息
     * @throws BusinessException 消息超长
     */
    public static void validateInput(int maxChars, String message) {
        if (message != null && message.length() > maxChars) {
            throw new BusinessException("消息长度不能超过" + maxChars + "字");
        }
    }
}
