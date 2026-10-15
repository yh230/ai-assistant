package com.system.mq;

import com.api.mq.MqEnvelope;
import com.api.mq.MqTopics;
import com.api.mq.RegistrationMqEvent;
import com.common.mq.IdempotentGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 站内信消费者测试
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @Mock private NotificationService notificationService;
    @Mock private IdempotentGuard idempotentGuard;
    @Mock private Acknowledgment ack;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new NotificationConsumer(notificationService, idempotentGuard, objectMapper);
    }

    private ConsumerRecord<String, String> record(MqEnvelope envelope) throws Exception {
        return new ConsumerRecord<>(MqTopics.REGISTRATION_EVENT, 0, 0L, "k", objectMapper.writeValueAsString(envelope));
    }

    private MqEnvelope envelope() throws Exception {
        RegistrationMqEvent event = new RegistrationMqEvent();
        event.setPatientId(7L);
        event.setRegistrationNo("GH1-3-x");
        event.setDoctorName("张医生");
        event.setPeriod(1);
        MqEnvelope envelope = new MqEnvelope();
        envelope.setEventId("e1");
        envelope.setEventType(RegistrationMqEvent.APPOINTMENT_CREATED);
        envelope.setPayload(objectMapper.writeValueAsString(event));
        return envelope;
    }

    @Test
    void shouldCreateAndAckOnFirstConsume() throws Exception {
        when(idempotentGuard.consumeOnce(eq("notification-consumer"), eq("e1"), any(Duration.class))).thenReturn(true);

        consumer.onMessage(record(envelope()), ack);

        verify(notificationService).createFromEvent(eq(RegistrationMqEvent.APPOINTMENT_CREATED), any(RegistrationMqEvent.class));
        verify(ack).acknowledge();
    }

    @Test
    void shouldSkipAndAckOnDuplicate() throws Exception {
        when(idempotentGuard.consumeOnce(eq("notification-consumer"), eq("e1"), any(Duration.class))).thenReturn(false);

        consumer.onMessage(record(envelope()), ack);

        verify(notificationService, never()).createFromEvent(anyString(), any());
        verify(ack).acknowledge();
    }

    @Test
    void shouldReleaseIdempotentKeyAndRethrowOnFailure() throws Exception {
        when(idempotentGuard.consumeOnce(eq("notification-consumer"), eq("e1"), any(Duration.class))).thenReturn(true);
        doThrow(new RuntimeException("db down")).when(notificationService).createFromEvent(anyString(), any());

        assertThrows(RuntimeException.class, () -> consumer.onMessage(record(envelope()), ack));

        verify(idempotentGuard).release("notification-consumer", "e1");
        verify(ack, never()).acknowledge();
    }
}
