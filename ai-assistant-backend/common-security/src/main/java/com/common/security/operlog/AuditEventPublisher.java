package com.common.security.operlog;

import com.api.dto.OperLogDTO;
import com.api.mq.MqEnvelope;
import com.api.mq.MqTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 审计事件发布器：将操作日志封装为信封发往 Kafka（best-effort，失败仅告警）
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布审计事件
     *
     * @param dto 操作日志
     */
    public void publish(OperLogDTO dto) {
        try {
            MqEnvelope envelope = new MqEnvelope();
            envelope.setEventId(UUID.randomUUID().toString());
            envelope.setEventType("AUDIT_LOG");
            envelope.setOccurredAt(System.currentTimeMillis());
            envelope.setPayload(objectMapper.writeValueAsString(dto));
            String json = objectMapper.writeValueAsString(envelope);
            kafkaTemplate.send(MqTopics.AUDIT_LOG, envelope.getEventId(), json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("事件发送失败: topic={}, eventId={}, err={}",
                                    MqTopics.AUDIT_LOG, envelope.getEventId(), ex.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("审计事件发布失败: {}", e.getMessage());
        }
    }
}
