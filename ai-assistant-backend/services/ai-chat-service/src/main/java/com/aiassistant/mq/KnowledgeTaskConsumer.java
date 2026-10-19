package com.aiassistant.mq;

import com.aiassistant.entity.KnowledgeDocument;
import com.aiassistant.rag.KnowledgeChunker;
import com.aiassistant.rag.KnowledgeStore;
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
    private final KnowledgeStore knowledgeStore;
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
        // 信封解析同样置于 try 内：无法解析或缺少事件 ID 的消息不能重试成功，记录日志后确认丢弃
        String eventId = null;
        try {
            MqEnvelope envelope = objectMapper.readValue(record.value(), MqEnvelope.class);
            eventId = envelope == null ? null : envelope.getEventId();
            if (eventId == null || eventId.isBlank()) {
                log.error("知识库任务消息缺少事件ID，确认丢弃: topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
                ack.acknowledge();
                return;
            }
            if (!idempotentGuard.consumeOnce(GROUP, eventId, IDEMPOTENT_TTL)) {
                ack.acknowledge();
                return;
            }
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
            int previousChunkCount = doc.getChunkCount() == null ? 0 : doc.getChunkCount();
            // 使用真实文档 ID 切片：切片 ID 为 documentId:index，供幂等覆盖与 chunk 级引用溯源
            List<Document> chunks = knowledgeChunker.split(doc.getId(), doc.getName(), doc.getContent());
            knowledgeStore.upsertDocument(event.getHospitalId(), doc.getId(), doc.getName(),
                    previousChunkCount, chunks);
            doc.setChunkCount(chunks.size());
            doc.setStatus(KnowledgeDocument.STATUS_READY);
            doc.setErrorMsg("");
            documentRepository.save(doc);
            ack.acknowledge();
        } catch (Exception e) {
            if (eventId == null) {
                // 解析阶段失败（无事件 ID），重试不可能成功，记录日志并确认，避免进入无限重试
                log.error("知识库任务消息解析失败，确认丢弃: topic={}, partition={}, offset={}, err={}",
                        record.topic(), record.partition(), record.offset(), e.getMessage(), e);
                ack.acknowledge();
                return;
            }
            // 业务处理失败释放幂等键：重试可重放同一事件（重放耗尽由错误处理器转 DLT）
            idempotentGuard.release(GROUP, eventId);
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
            KnowledgeDocument doc = documentRepository.findById(event.getDocumentId()).orElse(null);
            if (doc == null) {
                log.warn("知识库死信对应文档不存在，跳过: {}", event.getDocumentId());
                return;
            }
            if (doc.getStatus() != null && doc.getStatus() == KnowledgeDocument.STATUS_READY) {
                // 文档已就绪说明重复事件的死信晚于成功处理到达，保持就绪，禁止状态回退
                log.warn("知识库文档已就绪，忽略死信以免状态回退: {}", doc.getId());
                return;
            }
            doc.setStatus(KnowledgeDocument.STATUS_FAILED);
            doc.setErrorMsg("异步处理失败，请删除后重新上传");
            documentRepository.save(doc);
            log.warn("知识库文档处理失败已置为失败状态: {}", doc.getId());
        } catch (Exception e) {
            // 兜底：死信不重试；保存失败时文档停留在处理中，本批次接受，记录错误便于人工排查
            log.error("处理知识库DLT失败: topic={}, partition={}, offset={}, err={}",
                    record.topic(), record.partition(), record.offset(), e.getMessage(), e);
        } finally {
            // 无论解析/落库成败均确认一次，避免死信重放
            ack.acknowledge();
        }
    }
}
