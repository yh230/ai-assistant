package com.api.mq;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费错误处理公共配置：重试 3 次 × 1s，耗尽后转发 &lt;topic&gt;.DLT。
 * 仅当消费端引入 spring-kafka 时生效，各服务可通过自定义 DefaultErrorHandler 覆盖。
 *
 * @author yh
 * @date 2026-10-14
 */
@Configuration
@ConditionalOnClass(DefaultErrorHandler.class)
public class KafkaErrorConfiguration {

    @Bean
    @ConditionalOnMissingBean(DefaultErrorHandler.class)
    public DefaultErrorHandler kafkaDefaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(MqTopics.dlt(record.topic()), record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
