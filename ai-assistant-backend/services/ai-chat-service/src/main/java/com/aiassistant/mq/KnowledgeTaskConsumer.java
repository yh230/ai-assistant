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
     * <p>
     * 文档不存在（上传事务已回滚）或已就绪时直接确认；处理失败释放幂等键并抛出，
     * 由错误处理器重试，重试耗尽后转死信主题。
     *
     * @param record 消息
     * @param ack 手动确认
     */
    @KafkaListener(topics = MqTopics.KNOWLEDGE_TASK, groupId = GROUP)
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        MqEnvelope envelope = objectMapper.readValue(record.value(), MqEnvelope.class);
        if (!idempotentGuard.consumeOnce(GROUP, envelope.getEventId(), IDEMPOTENT_TTL)) {
            ack.acknowledge();
            return;
        }
        try {
            KnowledgeMqEvent event = objectMapper.readValue(envelope.getPayload(), KnowledgeMqEvent.class);
            KnowledgeDocument doc = documentRepository.findById(event.getDocumentId()).orElse(null);
            if (doc == null) {
                log.warn("知识库任务文档不存在，跳过: {}", event.getDocumentId());
                ack.acknowledge();
                return;
            }
            if (doc.getStatus() != null && doc.getStatus() == KnowledgeDocument.STATUS_READY) {
                log.info("知识库文档已就绪，跳过重复处理: {}", doc.getId());
                ack.acknowledge();
                return;
            }
            List<Document> chunks = knowledgeChunker.split(doc.getName(), doc.getContent());
            knowledgeVectorStore.addDocuments(event.getHospitalId(), chunks);
            doc.setChunkCount(chunks.size());
            doc.setStatus(KnowledgeDocument.STATUS_READY);
            doc.setErrorMsg(null);
            documentRepository.save(doc);
            ack.acknowledge();
        } catch (Exception e) {
            // 失败释放幂等键：重试可重放同一事件（重放耗尽由错误处理器转 DLT）
            idempotentGuard.release(GROUP, envelope.getEventId());
            throw e;
        }
    }

    /**
     * 消费知识库死信：将文档置为失败并写入提示信息
     * <p>
     * 死信只做状态兜底，解析失败仅记录日志，避免死信再次进入重试循环；无论成败均确认消息
     *
     * @param record 死信消息
     * @param ack 手动确认
     */
    @KafkaListener(topics = MqTopics.KNOWLEDGE_TASK_DLT, groupId = "knowledge-dlt")
    public void onDlt(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            MqEnvelope envelope = objectMapper.readValue(record.value(), MqEnvelope.class);
            KnowledgeMqEvent event = objectMapper.readValue(envelope.getPayload(), KnowledgeMqEvent.class);
            documentRepository.findById(event.getDocumentId()).ifPresent(doc -> {
                doc.setStatus(KnowledgeDocument.STATUS_FAILED);
                doc.setErrorMsg("异步处理失败，请删除后重新上传");
                documentRepository.save(doc);
                log.warn("知识库文档处理失败已置为失败状态: {}", doc.getId());
            });
        } catch (Exception e) {
            log.error("处理知识库DLT失败: {}", e.getMessage());
        }
        ack.acknowledge();
    }
}
