package com.aiassistant.config;

import com.aiassistant.rag.KnowledgeChunker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ai-chat 消费侧共享 Bean 装配
 *
 * @author yh
 * @date 2026-10-14
 */
@Configuration
public class KnowledgeConsumerConfig {

    /**
     * 知识库切片器 Bean：供知识库任务消费者注入（切片大小、重叠比例与检索配置同源）
     * <p>
     * KnowledgeServiceImpl 删除文档时按已写入切片数清理，不再自行构建切片器；此处仅补齐消费侧依赖
     *
     * @param aiProperties AI 配置（取切片大小与重叠比例）
     * @return 知识库切片器
     */
    @Bean
    public KnowledgeChunker knowledgeChunker(AiProperties aiProperties) {
        return new KnowledgeChunker(
                aiProperties.getRag().getChunkSize(),
                aiProperties.getRag().getChunkOverlap());
    }
}
