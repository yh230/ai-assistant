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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
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
    @Mock private KnowledgeStore knowledgeStore;
    @Mock private KnowledgeChunker knowledgeChunker;
    @Mock private IdempotentGuard idempotentGuard;
    @Mock private Acknowledgment ack;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KnowledgeTaskConsumer(documentRepository, knowledgeStore,
                knowledgeChunker, idempotentGuard, objectMapper);
    }

    private ConsumerRecord<String, String> record(KnowledgeMqEvent event) throws Exception {
        return record(MqTopics.KNOWLEDGE_TASK, event);
    }

    private ConsumerRecord<String, String> record(String topic, KnowledgeMqEvent event) throws Exception {
        MqEnvelope envelope = new MqEnvelope();
        envelope.setEventId("k1");
        envelope.setEventType(KnowledgeMqEvent.KNOWLEDGE_UPLOADED);
        envelope.setPayload(objectMapper.writeValueAsString(event));
        return new ConsumerRecord<>(topic, 0, 0L, "k", objectMapper.writeValueAsString(envelope));
    }

    private KnowledgeDocument document(int status) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(9L);
        doc.setHospitalId(1L);
        doc.setName("guide.md");
        doc.setContent("正文");
        doc.setStatus(status);
        return doc;
    }

    @Test
    void shouldChunkEmbedAndMarkReady() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        KnowledgeDocument doc = document(KnowledgeDocument.STATUS_PENDING);
        doc.setErrorMsg("历史失败信息");
        when(idempotentGuard.consumeOnce(eq("knowledge-consumer"), eq("k1"), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(9L)).thenReturn(Optional.of(doc));
        when(knowledgeChunker.split(eq(9L), eq("guide.md"), eq("正文"))).thenReturn(List.of());

        consumer.onMessage(record(event), ack);

        verify(knowledgeStore).upsertDocument(1L, 9L, "guide.md", 0, List.of());
        verify(documentRepository).save(argThat(d -> d.getStatus() == KnowledgeDocument.STATUS_READY
                && "".equals(d.getErrorMsg()) && d.getChunkCount() == 0));
        verify(ack).acknowledge();
    }

    @Test
    void shouldPassPreviousChunkCountAndNewChunksToStore() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        KnowledgeDocument doc = document(KnowledgeDocument.STATUS_PENDING);
        doc.setChunkCount(5);
        doc.setErrorMsg("上次处理失败");
        List<Document> chunks = List.of(
                Document.builder().id("9:0").text("第一片").build(),
                Document.builder().id("9:1").text("第二片").build());
        when(idempotentGuard.consumeOnce(eq("knowledge-consumer"), eq("k1"), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(9L)).thenReturn(Optional.of(doc));
        when(knowledgeChunker.split(9L, "guide.md", "正文")).thenReturn(chunks);

        consumer.onMessage(record(event), ack);

        verify(knowledgeStore).upsertDocument(1L, 9L, "guide.md", 5, chunks);
        verify(documentRepository).save(argThat(d -> d.getChunkCount() == 2
                && d.getStatus() == KnowledgeDocument.STATUS_READY
                && "".equals(d.getErrorMsg())));
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

        verify(knowledgeStore, never()).upsertDocument(any(), any(), any(), anyInt(), any());
        verify(documentRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    void shouldSkipWhenDocumentAlreadyReady() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        when(idempotentGuard.consumeOnce(eq("knowledge-consumer"), eq("k1"), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(9L)).thenReturn(Optional.of(document(KnowledgeDocument.STATUS_READY)));

        consumer.onMessage(record(event), ack);

        verify(knowledgeChunker, never()).split(any(), any(), any());
        verify(knowledgeStore, never()).upsertDocument(any(), any(), any(), anyInt(), any());
        verify(documentRepository, never()).save(any());
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

    @Test
    void shouldReleaseIdempotentKeyAndRethrowOnFailure() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        when(idempotentGuard.consumeOnce(eq("knowledge-consumer"), eq("k1"), any(Duration.class))).thenReturn(true);
        when(documentRepository.findById(9L)).thenReturn(Optional.of(document(KnowledgeDocument.STATUS_PENDING)));
        when(knowledgeChunker.split(any(), any(), any())).thenReturn(List.of());
        doThrow(new RuntimeException("embed down")).when(knowledgeStore)
                .upsertDocument(any(), any(), any(), anyInt(), any());

        assertThrows(RuntimeException.class, () -> consumer.onMessage(record(event), ack));

        verify(idempotentGuard).release("knowledge-consumer", "k1");
        verify(documentRepository, never()).save(any());
        verify(ack, never()).acknowledge();
    }

    @Test
    void shouldReleaseIdempotentKeyWhenPayloadMalformed() throws Exception {
        MqEnvelope envelope = new MqEnvelope();
        envelope.setEventId("k1");
        envelope.setEventType(KnowledgeMqEvent.KNOWLEDGE_UPLOADED);
        envelope.setPayload("{bad");
        String envelopeJson = objectMapper.writeValueAsString(envelope);
        when(idempotentGuard.consumeOnce(eq("knowledge-consumer"), eq("k1"), any(Duration.class))).thenReturn(true);

        assertThrows(Exception.class, () -> consumer.onMessage(
                new ConsumerRecord<>(MqTopics.KNOWLEDGE_TASK, 0, 0L, "k", envelopeJson), ack));

        verify(idempotentGuard).release("knowledge-consumer", "k1");
        verify(ack, never()).acknowledge();
    }

    @Test
    void shouldAckAndSkipWhenEnvelopeMalformed() {
        assertDoesNotThrow(() -> consumer.onMessage(new ConsumerRecord<>(
                MqTopics.KNOWLEDGE_TASK, 0, 0L, "k", "{bad"), ack));

        verifyNoInteractions(idempotentGuard);
        verify(documentRepository, never()).findById(any());
        verify(ack).acknowledge();
    }

    @Test
    void shouldAckAndSkipWhenEventIdMissing() throws Exception {
        MqEnvelope envelope = new MqEnvelope();
        envelope.setEventType(KnowledgeMqEvent.KNOWLEDGE_UPLOADED);
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        envelope.setPayload(objectMapper.writeValueAsString(event));
        String envelopeJson = objectMapper.writeValueAsString(envelope);

        assertDoesNotThrow(() -> consumer.onMessage(new ConsumerRecord<>(
                MqTopics.KNOWLEDGE_TASK, 0, 0L, "k", envelopeJson), ack));

        verifyNoInteractions(idempotentGuard);
        verify(documentRepository, never()).findById(any());
        verify(ack).acknowledge();
    }

    @Test
    void shouldMarkDocumentFailedOnDlt() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        KnowledgeDocument doc = document(KnowledgeDocument.STATUS_PENDING);
        when(documentRepository.findById(9L)).thenReturn(Optional.of(doc));

        consumer.onDlt(record(MqTopics.KNOWLEDGE_TASK_DLT, event), ack);

        verify(documentRepository).save(argThat(d -> d.getStatus() == KnowledgeDocument.STATUS_FAILED
                && d.getErrorMsg() != null && !d.getErrorMsg().isBlank()));
        verify(ack).acknowledge();
    }

    @Test
    void shouldNotRegressReadyDocumentOnDlt() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        KnowledgeDocument doc = document(KnowledgeDocument.STATUS_READY);
        doc.setChunkCount(3);
        when(documentRepository.findById(9L)).thenReturn(Optional.of(doc));

        consumer.onDlt(record(MqTopics.KNOWLEDGE_TASK_DLT, event), ack);

        verify(documentRepository, never()).save(any());
        assertEquals(KnowledgeDocument.STATUS_READY, doc.getStatus());
        assertNull(doc.getErrorMsg());
        verify(ack).acknowledge();
    }

    @Test
    void shouldIgnoreMalformedDltWithoutThrowing() {
        assertDoesNotThrow(() -> consumer.onDlt(new ConsumerRecord<>(
                MqTopics.KNOWLEDGE_TASK_DLT, 0, 0L, "k", "{bad"), ack));

        verify(documentRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    void shouldIgnoreDltWhenDocumentMissing() throws Exception {
        KnowledgeMqEvent event = new KnowledgeMqEvent();
        event.setDocumentId(9L);
        event.setHospitalId(1L);
        when(documentRepository.findById(9L)).thenReturn(Optional.empty());

        consumer.onDlt(record(MqTopics.KNOWLEDGE_TASK_DLT, event), ack);

        verify(documentRepository, never()).save(any());
        verify(ack).acknowledge();
    }
}
