package com.aiassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助手全局配置属性。
 * <p>
 * 绑定配置前缀 {@code ai.assistant}，仅承载「模型连接」类配置（多模型接入、默认模型）。
 * <b>角色（人设 / 提示词）完全由数据库驱动</b>，不出现在配置文件中，
 * 统一通过「角色管理」页面维护，实现配置与业务数据的彻底解耦。
 *
 * @author AI Assistant
 */
@Component
@ConfigurationProperties(prefix = "ai.assistant")
@Data
public class AssistantProperties {

    /** 默认模型ID，用户未指定时使用 */
    private String defaultModel = "openai";

    /** 模型定义集合，key 为模型ID（如 openai、qwen、ollama） */
    private Map<String, ModelProperties> models = new LinkedHashMap<>();

    /** 主模型失败时的备用模型顺序 */
    private List<String> fallbackModelIds = new ArrayList<>();

    /** 各场景温度 */
    private Map<String, Double> sceneTemperature = new LinkedHashMap<>(Map.of(
            "chat", 0.7, "triage", 0.2, "rag", 0.2));

    /**
     * 解析实际使用的模型ID：请求模型存在则用之，否则回退默认模型。
     *
     * @param requested 用户请求的模型ID（可为空）
     * @return 实际模型ID
     */
    public String resolveModelId(String requested) {
        if (requested != null && models.containsKey(requested)) {
            return requested;
        }
        return defaultModel;
    }
}
