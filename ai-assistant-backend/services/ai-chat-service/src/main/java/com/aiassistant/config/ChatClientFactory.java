package com.aiassistant.config;

import com.aiassistant.tool.WeatherTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 工厂：按模型ID构建并缓存 {@link ChatClient}。
 * <p>
 * {@link ChatClient} 在 Spring AI 中设计为构建一次、运行时通过 {@code prompt()} 覆盖
 * system/messages/options 的无状态客户端；这里按模型缓存，避免每个请求重复构建。
 * 天气工具只在员工侧默认装配，患者侧使用不含默认工具的 base client，再按请求注入业务工具回调。
 * 模型注册表重建后 {@link ChatModel} 实例会变化，缓存按实例身份自动失效并重建。
 *
 * @author AI Assistant
 */
@Slf4j
@Component
public class ChatClientFactory {

    private final ChatModelRegistry modelRegistry;
    private final WeatherTools weatherTools;

    /** 员工侧 ChatClient 缓存（带天气工具） */
    private final Map<String, CachedClient> staffClients = new ConcurrentHashMap<>();

    /** 患者侧 ChatClient 缓存（不含默认工具） */
    private final Map<String, CachedClient> baseClients = new ConcurrentHashMap<>();

    public ChatClientFactory(ChatModelRegistry modelRegistry, WeatherTools weatherTools) {
        this.modelRegistry = modelRegistry;
        this.weatherTools = weatherTools;
    }

    /**
     * 获取员工侧 ChatClient（带天气工具）。
     *
     * @param modelId 模型ID
     * @return 缓存的 ChatClient
     * @throws IllegalStateException 模型不存在或未启用
     */
    public ChatClient getChatClient(String modelId) {
        ChatModel model = requireModel(modelId);
        return staffClients.compute(modelId, (id, cached) ->
                        cached != null && cached.model() == model
                                ? cached
                                : new CachedClient(model,
                                ChatClient.builder(model).defaultTools(weatherTools).build()))
                .client();
    }

    /**
     * 获取患者侧 base ChatClient（不含默认工具，业务工具按请求注入）。
     *
     * @param modelId 模型ID
     * @return 缓存的 ChatClient
     * @throws IllegalStateException 模型不存在或未启用
     */
    public ChatClient getBaseChatClient(String modelId) {
        ChatModel model = requireModel(modelId);
        return baseClients.compute(modelId, (id, cached) ->
                        cached != null && cached.model() == model
                                ? cached
                                : new CachedClient(model, ChatClient.builder(model).build()))
                .client();
    }

    /**
     * 获取可用模型实例。
     *
     * @param modelId 模型ID
     * @return ChatModel 实例
     * @throws IllegalStateException 模型不存在或未启用
     */
    private ChatModel requireModel(String modelId) {
        return modelRegistry.getModel(modelId)
                .orElseThrow(() -> new IllegalStateException("未找到可用的模型: " + modelId));
    }

    /**
     * 缓存的 ChatClient 及其底层模型实例（用于识别模型注册表重建）。
     *
     * @param model  底层模型
     * @param client 已构建的 ChatClient
     */
    private record CachedClient(ChatModel model, ChatClient client) {
    }
}
