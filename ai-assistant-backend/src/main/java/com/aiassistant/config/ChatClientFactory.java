package com.aiassistant.config;

import com.aiassistant.tool.WeatherTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ChatClient 工厂：根据模型ID构建携带天气工具的 {@link ChatClient}。
 * <p>
 * 每次请求独立构建 ChatClient，天然支持多模型运行时切换，且避免持有长生命周期的有状态客户端。
 * 天气查询通过 Tool Calling 注入，AI 在对话中遇到天气问题会自动调用真实数据。
 *
 * @author AI Assistant
 */
@Slf4j
@Component
public class ChatClientFactory {

    private final ChatModelRegistry modelRegistry;
    private final WeatherTools weatherTools;

    public ChatClientFactory(ChatModelRegistry modelRegistry, WeatherTools weatherTools) {
        this.modelRegistry = modelRegistry;
        this.weatherTools = weatherTools;
    }

    /**
     * 为指定模型构建 ChatClient（要求模型必须可用）。
     *
     * @param modelId 模型ID
     * @return 构建好的 ChatClient
     * @throws IllegalStateException 当模型不存在或未启用时抛出
     */
    public ChatClient getChatClient(String modelId) {
        ChatModel model = modelRegistry.getModel(modelId)
                .orElseThrow(() -> new IllegalStateException("未找到可用的模型: " + modelId));
        return ChatClient.builder(model)
                .defaultTools(weatherTools)
                .build();
    }

    /**
     * 安全获取 ChatClient：模型不可用时返回空，便于上层决定降级策略。
     *
     * @param modelId 模型ID
     * @return ChatClient（Optional）
     */
    public Optional<ChatClient> getChatClientSafely(String modelId) {
        return modelRegistry.getModel(modelId)
                .map(model -> ChatClient.builder(model).defaultTools(weatherTools).build());
    }
}
