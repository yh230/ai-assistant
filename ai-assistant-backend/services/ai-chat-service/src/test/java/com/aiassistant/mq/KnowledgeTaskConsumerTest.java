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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 知识库任务消费者测试
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeTaskConsumerTest {

    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private KnowledgeVectorStore knowledgeVectorStore;
    @Mock private KnowledgeChunker knowledgeChunker;
    @Mock private IdempotentGuard idempotentGuard;
    @Mock private Acknowledgment ack;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KnowledgeTaskConsumer(documentRepository, knowledgeVectorStore,
                knowledgeChunker, idempotentGuard, objectMapper);
    }

    private ConsumerRecord<String, String> record(KnowledgeMqEvent event) throws Exception {
        MqEnvelope envelope = new MqEnvelope();
        envelope.setEventId("k1");
        envelope.setEventType(KnowledgeMqEvent.KNOWLEDGE_UPLOADED);
        envelope.setPayload(objectMapper.writeValueAsString(event));
        return new ConsumerRecord<>(MqTopics.KNOWLEDGE_TASK, 0, 0L, "k",
                objectMapper.writeValueAsString(envelope));
    }

    @Test
    void shouldChunkEmbedAndMarkReady() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(9L);
        doc.setHospitalId(1L);
        doc.setName("guide.md");
        doc.setContent("正文");
        doc.setStatus(KnowledgeDocument.STATUS_PENDING);
        when(idempotentGuard.consumeOnce(eq("knowledge-consumer"), eq("k1"), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(9L)).thenReturn(Optional.of(doc));
        when(knowledgeChunker.split(eq("guide.md"), eq("正文"))).thenReturn(List.of());

        consumer.onMessage(record(event), ack);

        verify(knowledgeVectorStore).addDocuments(eq(1L), any());
        verify(documentRepository).save(argThat(d -> d.getStatus() == KnowledgeDocument.STATUS_READY));
        verify(ack).acknowledge();
    }

    @Test
    void shouldSkipWhenDocumentMissingButStillAck() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        when(idempotentGuard.consumeOnce(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(9L)).thenReturn(Optional.empty());

        consumer.onMessage(record(event), ack);

        verify(knowledgeVectorStore, never()).addDocuments(any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void shouldSkipOnDuplicateEvent() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        when(idempotentGuard.consumeOnce(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        consumer.onMessage(record(event), ack);

        verify(documentRepository, never()).findById(any());
        verify(ack).acknowledge();
    }
}
