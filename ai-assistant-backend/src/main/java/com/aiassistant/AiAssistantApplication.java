package com.aiassistant;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 助手后端 - 主启动类
 * <p>
 * 排除 Spring AI 的 OpenAI 自动配置：本项目所有模型均通过 {@code ChatModelRegistry}
 * 依据 {@code application.yml} 中的 {@code ai.assistant.models} 配置手动构建，
 * 不依赖框架自动创建的 OpenAi* Bean。若不排除，框架会在缺少
 * {@code spring.ai.openai.api-key} 时因无法实例化 OpenAiAudioSpeechModel 等 Bean 而导致启动失败。
 * <p>
 * 变更点（Sa-Token→Spring Security 迁移）：迁移回 Spring Security 后，方法级权限开关由
 * {@code config/SecurityConfig} 的 {@code @EnableMethodSecurity} 承担，本类不声明该注解。
 *
 * @author yh
 * @date 2026-10-12
 */
@EnableScheduling
@SpringBootApplication(exclude = {
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class
})
public class AiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }
}
