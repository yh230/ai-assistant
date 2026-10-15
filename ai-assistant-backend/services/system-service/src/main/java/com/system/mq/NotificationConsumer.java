package com.system.mq;

import com.api.mq.MqEnvelope;
import com.api.mq.MqTopics;
import com.api.mq.RegistrationMqEvent;
import com.common.mq.IdempotentGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 站内信消费者：幂等去重后渲染落库并手动 ack
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    /** 消费组 */
    public static final String GROUP = "notification-consumer";

    /** 幂等键保留时长 */
    private static final Duration IDEMPOTENT_TTL = Duration.ofDays(7);

    private final NotificationService notificationService;
    private final IdempotentGuard idempotentGuard;
    private final ObjectMapper objectMapper;

    /**
     * 消费预约事件并渲染站内信落库
     *
     * @param record 消息
     * @param ack 手动确认
     */
    @KafkaListener(topics = MqTopics.REGISTRATION_EVENT, groupId = GROUP)
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        MqEnvelope envelope = objectMapper.readValue(record.value(), MqEnvelope.class);
        if (idempotentGuard.consumeOnce(GROUP, envelope.getEventId(), IDEMPOTENT_TTL)) {
            try {
                RegistrationMqEvent event = objectMapper.readValue(envelope.getPayload(), RegistrationMqEvent.class);
                notificationService.createFromEvent(envelope.getEventType(), event);
            } catch (Exception e) {
                // 失败释放幂等键：重试可重放同一事件（重放耗尽由错误处理器转 DLT）
                idempotentGuard.release(GROUP, envelope.getEventId());
                throw e;
            }
        }
        ack.acknowledge();
    }
}
