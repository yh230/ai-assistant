package com.aiassistant.config;

/**
 * 支持的模型提供方类型。
 * <p>
 * 新增厂商时：1）在此枚举补充类型；2）在 {@code ChatModelRegistry#buildChatModel} 中补充对应构建分支。
 * 若厂商兼容 OpenAI 协议（如 DeepSeek、通义兼容模式、智谱），直接复用 {@link #OPENAI} 即可，无需改动代码。
 *
 * @author AI Assistant
 */
public enum ModelType {

    /** OpenAI 及所有兼容 OpenAI 协议的服务 */
    OPENAI("openai"),

    /** 阿里云百炼 / 通义千问 DashScope（兼容模式同样复用 OPENAI） */
    DASHSCOPE("dashscope"),

    /** Ollama 本地模型 */
    OLLAMA("ollama");

    private final String value;

    ModelType(String value) {
        this.value = value;
    }

    /**
     * 根据配置字符串解析模型类型，未知类型默认按 OpenAI 兼容处理（提高容错）。
     *
     * @param value 配置值
     * @return 模型类型，非空
     */
    public static ModelType fromValue(String value) {
        if (value == null) {
            return OPENAI;
        }
        for (ModelType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return OPENAI;
    }
}
