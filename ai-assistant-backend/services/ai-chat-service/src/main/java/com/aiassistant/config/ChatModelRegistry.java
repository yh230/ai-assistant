package com.aiassistant.config;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心：依据配置构建并缓存可用的 {@link ChatModel} 实例。
 * <p>
 * 设计目标：
 * <ul>
 *     <li>支持同时接入多个大模型提供方（OpenAI / DeepSeek / 通义 / Ollama...），运行时按模型ID取用；</li>
 *     <li>对无效或禁用的模型自动跳过，避免启动失败；</li>
 *     <li>新增厂商只需在 {@link #buildChatModel} 中补充构建分支，调用方无感知（开闭原则）。</li>
 * </ul>
 *
 * @author AI Assistant
 */
@Slf4j
@Component
public class ChatModelRegistry {

    /** 视为无效的 API Key 占位值集合 */
    private static final Set<String> INVALID_API_KEYS = Set.of("your-api-key-here", "dummy-key", "sk-xxx", "");

    /** OpenAI 官方默认 Base URL，未配置 baseUrl 时回退使用 */
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";

    private final AssistantProperties assistantProperties;
    private final ObservationRegistry observationRegistry;
    private final Map<String, ChatModel> modelCache = new ConcurrentHashMap<>();

    public ChatModelRegistry(AssistantProperties assistantProperties, ObservationRegistry observationRegistry) {
        this.assistantProperties = assistantProperties;
        this.observationRegistry = observationRegistry;
        initModels();
    }

    /**
     * 启动时根据配置构建所有「启用且凭据有效」的模型实例并放入缓存。
     */
    private void initModels() {
        for (Map.Entry<String, ModelProperties> entry : assistantProperties.getModels().entrySet()) {
            String modelId = entry.getKey();
            ModelProperties props = entry.getValue();
            if (props == null || !Boolean.TRUE.equals(props.getEnabled())) {
                log.debug("模型[{}]未启用，跳过", modelId);
                continue;
            }
            if (!isValidApiKey(props.getApiKey())) {
                log.warn("模型[{}]未配置有效 API Key，已跳过初始化", modelId);
                continue;
            }
            ChatModel chatModel = buildChatModel(props);
            if (chatModel != null) {
                modelCache.put(modelId, chatModel);
                log.info("已加载模型[{}]: type={}, baseUrl={}, model={}",
                        modelId, props.getType(), props.getBaseUrl(), props.getModel());
            }
        }
    }

    /**
     * 根据模型类型构建对应的 ChatModel 实例。
     *
     * @param props 模型配置
     * @return ChatModel 实例；不支持的类型返回 null
     */
    private ChatModel buildChatModel(ModelProperties props) {
        ModelType type = ModelType.fromValue(props.getType());
        switch (type) {
            case OPENAI:
            case DASHSCOPE:
                // DashScope 等兼容 OpenAI 协议的服务，复用 OpenAI 接入
                OpenAiApi api = OpenAiApi.builder()
                        .baseUrl(resolveBaseUrl(props.getBaseUrl()))
                        .apiKey(props.getApiKey())
                        .build();
                OpenAiChatOptions options = OpenAiChatOptions.builder()
                        .model(props.getModel())
                        .temperature(props.getTemperature() != null ? props.getTemperature() : 0.8)
                        .build();
                return OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(options)
                        .observationRegistry(observationRegistry)
                        .build();
            case OLLAMA:
                log.warn("Ollama 模型类型暂未实现，模型[{}]已跳过", props.getBaseUrl());
                return null;
            default:
                log.warn("不支持的模型类型: {}", props.getType());
                return null;
        }
    }

    /**
     * 解析 API Base URL：未配置时回退 OpenAI 官方地址，避免向 OpenAI 兼容服务传入 null。
     *
     * @param baseUrl 配置中的 baseUrl
     * @return 可用的 baseUrl
     */
    private String resolveBaseUrl(String baseUrl) {
        return (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_OPENAI_BASE_URL : baseUrl;
    }

    /**
     * 获取指定模型，不存在时返回空。
     *
     * @param modelId 模型ID
     * @return 模型实例（Optional）
     */
    public Optional<ChatModel> getModel(String modelId) {
        return Optional.ofNullable(modelCache.get(modelId));
    }

    /**
     * 判断指定模型是否可用（已加载且启用）。
     *
     * @param modelId 模型ID
     * @return 是否可用
     */
    public boolean isActive(String modelId) {
        return modelCache.containsKey(modelId);
    }

    /**
     * 返回所有可用模型ID。
     *
     * @return 可用模型ID集合
     */
    public Set<String> getActiveModelIds() {
        return new LinkedHashSet<>(modelCache.keySet());
    }

    /**
     * 构建故障转移模型链：主模型优先（仅当可用），随后按配置顺序追加可用备用模型，自动去重。
     *
     * @param primary 主模型ID
     * @return 有序模型链（可能为空，表示无可用模型）
     */
    public List<String> getFallbackChain(String primary) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        if (isActive(primary)) {
            chain.add(primary);
        }
        if (assistantProperties.getFallbackModelIds() != null) {
            assistantProperties.getFallbackModelIds().forEach(id -> {
                if (isActive(id)) {
                    chain.add(id);
                }
            });
        }
        return List.copyOf(chain);
    }

    /**
     * 校验 API Key 是否有效（非空、非占位值）。
     *
     * @param apiKey API Key
     * @return 是否有效
     */
    private boolean isValidApiKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank() && !INVALID_API_KEYS.contains(apiKey.trim());
    }
}
