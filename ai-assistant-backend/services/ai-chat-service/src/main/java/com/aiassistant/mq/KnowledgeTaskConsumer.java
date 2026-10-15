package com.aiassistant.mq;

import com.aiassistant.entity.KnowledgeDocument;
import com.aiassistant.rag.KnowledgeChunker;
import com.aiassistant.rag.KnowledgeVectorStore;
import com.aiassistant.repository.KnowledgeDocumentRepository;
import com.api.mq.KnowledgeMqEvent;
import com.api.mq.MqEnvelope;
import com.api.mq.MqTopics;
import com.common.mq.IdempotentGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.ai.document.Document;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 知识库任务消费者：幂等去重后切片向量化并置文档就绪
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTaskConsumer {

    /** 消费组 */
    public static final String GROUP = "knowledge-consumer";

    /** 幂等键保留时长 */
    private static final Duration IDEMPOTENT_TTL = Duration.ofDays(7);

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeVectorStore knowledgeVectorStore;
    private final KnowledgeChunker knowledgeChunker;
    private final IdempotentGuard idempotentGuard;
    private final ObjectMapper objectMapper;

    /**
     * 消费知识库任务并完成切片向量化
     *
     * @param record 消息
     * @param ack 手动确认
     */
    @KafkaListener(topics = MqTopics.KNOWLEDGE_TASK, groupId = GROUP)
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        MqEnvelope envelope = objectMapper.readValue(record.value(), MqEnvelope.class);
        if (idempotentGuard.consumeOnce(GROUP, envelope.getEventId(), IDEMPOTENT_TTL)) {
            try {
                KnowledgeMqEvent event = objectMapper.readValue(envelope.getPayload(), KnowledgeMqEvent.class);
                documentRepository.findById(event.getDocumentId()).ifPresentOrElse(doc -> {
                    List<Document> chunks = knowledgeChunker.split(doc.getName(), doc.getContent());
                    knowledgeVectorStore.addDocuments(event.getHospitalId(), chunks);
                    doc.setChunkCount(chunks.size());
                    doc.setStatus(KnowledgeDocument.STATUS_READY);
                    documentRepository.save(doc);
                }, () -> log.warn("知识库任务文档不存在，跳过: {}", event.getDocumentId()));
            } catch (Exception e) {
                // 失败释放幂等键：重试可重放同一事件（重放耗尽由错误处理器转 DLT）
                idempotentGuard.release(GROUP, envelope.getEventId());
                throw e;
            }
        }
        ack.acknowledge();
    }
}
