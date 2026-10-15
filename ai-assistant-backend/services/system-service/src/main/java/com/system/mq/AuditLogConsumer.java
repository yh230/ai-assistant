package com.system.mq;

import com.api.dto.OperLogDTO;
import com.api.mq.MqEnvelope;
import com.api.mq.MqTopics;
import com.common.mq.IdempotentGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 审计日志消费者：幂等去重后落库并手动 ack
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogConsumer {

    /** 消费组 */
    public static final String GROUP = "audit-consumer";

    /** 幂等键保留时长 */
    private static final Duration IDEMPOTENT_TTL = Duration.ofDays(7);

    private final OperLogService operLogService;
    private final IdempotentGuard idempotentGuard;
    private final ObjectMapper objectMapper;

    /**
     * 消费审计事件并落库
     *
     * @param record 消息
     * @param ack 手动确认
     */
    @KafkaListener(topics = MqTopics.AUDIT_LOG, groupId = GROUP)
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        MqEnvelope envelope = objectMapper.readValue(record.value(), MqEnvelope.class);
        if (idempotentGuard.consumeOnce(GROUP, envelope.getEventId(), IDEMPOTENT_TTL)) {
            operLogService.save(objectMapper.readValue(envelope.getPayload(), OperLogDTO.class));
        }
        ack.acknowledge();
        // 保存失败将抛出并由 DefaultErrorHandler 重试/转 DLT，此时不 ack
    }
}
