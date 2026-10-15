package com.aiassistant.mq;

import com.api.mq.KnowledgeMqEvent;
import com.api.mq.MqEnvelope;
import com.api.mq.MqTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 知识库任务事件发布器：将上传事件封装为信封发往 Kafka（best-effort，失败仅告警）
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTaskPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布知识文档已上传事件
     *
     * @param documentId 文档 ID
     * @param hospitalId 医院 ID（消费端据此定位本院向量文件）
     */
    public void publishUploaded(Long documentId, Long hospitalId) {
        try {
            KnowledgeMqEvent event = new KnowledgeMqEvent();
            event.setDocumentId(documentId);
            event.setHospitalId(hospitalId);
            MqEnvelope envelope = new MqEnvelope();
            envelope.setEventId(UUID.randomUUID().toString());
            envelope.setEventType(KnowledgeMqEvent.KNOWLEDGE_UPLOADED);
            envelope.setOccurredAt(System.currentTimeMillis());
            envelope.setPayload(objectMapper.writeValueAsString(event));
            kafkaTemplate.send(MqTopics.KNOWLEDGE_TASK, envelope.getEventId(),
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("知识库任务事件发布失败: {}", e.getMessage());
        }
    }
}
