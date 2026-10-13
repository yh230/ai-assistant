package com.aiassistant.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StringUtils;

/**
 * 本地嵌入模型配置（transformers ONNX，CPU 推理，零新增密钥）
 *
 * @author yh
 * @date 2026-10-13
 */
@Configuration
public class EmbeddingConfig {

    /**
     * 嵌入模型 Bean：优先使用配置的本地模型文件，缺省走框架内置模型
     *
     * @param aiProperties AI 配置（embeddingModelPath 可空）
     * @return 嵌入模型
     * @throws Exception 模型加载或下载失败
     */
    @Bean
    public EmbeddingModel embeddingModel(AiProperties aiProperties) throws Exception {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        String path = aiProperties.getRag().getEmbeddingModelPath();
        if (StringUtils.hasText(path)) {
            model.setModelResource(new FileSystemResource(path));
            model.setTokenizerResource(new FileSystemResource(path.replace(".onnx", "-tokenizer.json")));
        }
        model.afterPropertiesSet();
        return model;
    }
}
