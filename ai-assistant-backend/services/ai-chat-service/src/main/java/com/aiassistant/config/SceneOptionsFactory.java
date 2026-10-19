package com.aiassistant.config;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 场景级请求参数工厂：按会话场景（chat/triage/rag）解析温度，并统一设置输出 token 上限。
 * <p>
 * 构建模型时仅设置模型名与默认温度；场景温度与 maxTokens 需要在每次请求时确定，
 * 避免同一模型实例在不同场景间复用固定参数。
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
public class SceneOptionsFactory {

    /** 场景未配置时的默认温度 */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    private final AssistantProperties assistantProperties;
    private final AiProperties aiProperties;

    public SceneOptionsFactory(AssistantProperties assistantProperties, AiProperties aiProperties) {
        this.assistantProperties = assistantProperties;
        this.aiProperties = aiProperties;
    }

    /**
     * 构建指定场景的请求参数。
     *
     * @param scene 场景标识（chat/triage/rag，大小写不敏感）
     * @return 请求级 OpenAiChatOptions
     */
    public OpenAiChatOptions options(String scene) {
        String key = scene == null ? "chat" : scene.toLowerCase(Locale.ROOT);
        double temperature = assistantProperties.getSceneTemperature() != null
                ? assistantProperties.getSceneTemperature().getOrDefault(key, DEFAULT_TEMPERATURE)
                : DEFAULT_TEMPERATURE;
        Integer maxTokens = aiProperties.getChat() != null ? aiProperties.getChat().getMaxTokens() : null;
        // Spring AI 1.0.x 的 OpenAiChatOptions 支持 streamUsage：请求 OpenAI 兼容服务在流式响应末尾返回 usage，
        // 否则 ai.model.tokens 指标永远无数据（多数兼容服务默认不返回流式用量）
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(temperature)
                .streamUsage(true);
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        return builder.build();
    }
}
