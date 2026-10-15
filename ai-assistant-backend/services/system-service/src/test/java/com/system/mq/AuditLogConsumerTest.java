package com.system.mq;

import com.api.dto.OperLogDTO;
import com.api.mq.MqEnvelope;
import com.api.mq.MqTopics;
import com.common.mq.IdempotentGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.service.OperLogService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 审计日志消费者测试
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class AuditLogConsumerTest {

    @Mock private OperLogService operLogService;
    @Mock private IdempotentGuard idempotentGuard;
    @Mock private Acknowledgment ack;

    private AuditLogConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AuditLogConsumer(operLogService, idempotentGuard, new ObjectMapper());
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(MqTopics.AUDIT_LOG, 0, 0L, "k", value);
    }

    @Test
    void shouldSaveAndAckOnFirstConsume() throws Exception {
        OperLogDTO dto = new OperLogDTO();
        dto.setModule("用户管理");
        dto.setAction("新增用户");
        MqEnvelope envelope = new MqEnvelope();
        envelope.setEventId("e1");
        envelope.setEventType("AUDIT_LOG");
        envelope.setPayload(new ObjectMapper().writeValueAsString(dto));
        when(idempotentGuard.consumeOnce(eq("audit-consumer"), eq("e1"), any(Duration.class))).thenReturn(true);

        consumer.onMessage(record(new ObjectMapper().writeValueAsString(envelope)), ack);

        verify(operLogService).save(any(OperLogDTO.class));
        verify(ack).acknowledge();
    }

    @Test
    void shouldSkipAndAckOnDuplicate() throws Exception {
        MqEnvelope envelope = new MqEnvelope();
        envelope.setEventId("e1");
        envelope.setPayload("{}");
        when(idempotentGuard.consumeOnce(eq("audit-consumer"), eq("e1"), any(Duration.class))).thenReturn(false);

        consumer.onMessage(record(new ObjectMapper().writeValueAsString(envelope)), ack);

        verify(operLogService, never()).save(any());
        verify(ack).acknowledge();
    }
}
