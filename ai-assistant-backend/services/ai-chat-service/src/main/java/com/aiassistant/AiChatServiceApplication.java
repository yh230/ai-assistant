package com.aiassistant;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI 聊天服务启动类
 * <p>
 * 排除 Spring AI 的 OpenAI 自动配置：模型统一由 ai.assistant.models 配置经 ChatModelRegistry 手动构建
 *
 * @author yh
 * @date 2026-10-12
 */
@EnableFeignClients("com.api.client")
@SpringBootApplication(scanBasePackages = {"com.aiassistant", "com.common", "com.api"},
        exclude = {
                OpenAiChatAutoConfiguration.class,
                OpenAiEmbeddingAutoConfiguration.class,
                OpenAiImageAutoConfiguration.class,
                OpenAiAudioSpeechAutoConfiguration.class,
                OpenAiAudioTranscriptionAutoConfiguration.class,
                OpenAiModerationAutoConfiguration.class
        })
public class AiChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiChatServiceApplication.class, args);
    }
}
