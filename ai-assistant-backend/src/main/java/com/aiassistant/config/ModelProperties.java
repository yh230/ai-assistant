package com.aiassistant.config;

import lombok.Data;

/**
 * 模型配置：对接一个大模型提供方所需的连接与身份参数。
 * <p>
 * 对应配置 {@code ai.assistant.models.<modelId>}，新增厂商时复制一份并修改 type/baseUrl/apiKey/model 即可。
 *
 * @author AI Assistant
 */
@Data
public class ModelProperties {

    /** 模型类型，对应 {@link ModelType}，决定底层 ChatModel 实现 */
    private String type = "openai";

    /** API Base URL；OpenAI 兼容协议的服务（DeepSeek、通义兼容模式、智谱等）可复用 openai 类型 */
    private String baseUrl;

    /** API Key */
    private String apiKey;

    /** 模型名称，如 deepseek-chat / gpt-4o-mini / qwen-plus */
    private String model;

    /** 是否启用该模型 */
    private Boolean enabled = Boolean.FALSE;

    /** 采样温度（可选，为空时由 ChatModel 取默认值） */
    private Double temperature;
}
