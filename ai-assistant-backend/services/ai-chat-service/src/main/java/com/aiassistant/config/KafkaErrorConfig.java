package com.aiassistant.config;

import com.aiassistant.rag.KnowledgeChunker;
import com.api.mq.MqTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * ai-chat Kafka 装配：消费错误处理（重试耗尽转死信）与消费侧共享 Bean
 *
 * @author yh
 * @date 2026-10-14
 */
@Configuration
public class KafkaErrorConfig {

    /**
     * 默认错误处理器（重试 3 次 × 1s，耗尽转 &lt;topic&gt;.DLT）
     *
     * @param kafkaTemplate Kafka 模板
     * @return 错误处理器
     */
    @Bean
    public DefaultErrorHandler kafkaDefaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        MqTopics.dlt(record.topic()), record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }

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
