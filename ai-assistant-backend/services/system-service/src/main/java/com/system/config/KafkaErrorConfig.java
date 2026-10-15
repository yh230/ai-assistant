package com.system.config;

import com.api.mq.MqTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费错误处理：固定退避重试后转发死信主题
 *
 * @author yh
 * @date 2026-10-14
 */
@Configuration
public class KafkaErrorConfig {

    /**
     * 默认错误处理器（重试 3 次 × 1s，耗尽转 <topic>.DLT）
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
}
