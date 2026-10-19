package com.aiassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StringUtils;

/**
 * 本地嵌入模型配置（transformers ONNX，CPU 推理，零新增密钥）
 * <p>
 * 模型加载失败时返回不可用包装并记录错误，不再阻断应用启动：知识库检索按无命中降级，
 * 摄取任务由 Kafka 重试/死信兜底，其余对话能力保持可用。
 *
 * @author yh
 * @date 2026-10-13
 */
@Slf4j
@Configuration
public class EmbeddingConfig {

    /**
     * 嵌入模型 Bean：本地模型为首选嵌入实现，优先使用配置的本地模型文件，缺省走框架内置模型
     *
     * @param aiProperties AI 配置（embeddingModelPath 可空）
     * @return 嵌入模型（加载失败时为不可用包装）
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel(AiProperties aiProperties) {
        try {
            TransformersEmbeddingModel model = new TransformersEmbeddingModel();
            String path = aiProperties.getRag().getEmbeddingModelPath();
            if (StringUtils.hasText(path)) {
                model.setModelResource(new FileSystemResource(path));
                model.setTokenizerResource(new FileSystemResource(path.replace(".onnx", "-tokenizer.json")));
            }
            model.afterPropertiesSet();
            return new AvailabilityAwareEmbeddingModel(model);
        } catch (Exception e) {
            log.error("加载本地 embedding 模型失败，知识库检索/摄取将按不可用降级: {}", e.getMessage(), e);
            return new AvailabilityAwareEmbeddingModel(null, e.getMessage());
        }
    }
}
