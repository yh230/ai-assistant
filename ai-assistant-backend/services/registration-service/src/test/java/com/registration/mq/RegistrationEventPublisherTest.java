package com.registration.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 预约域事件发布器测试
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class RegistrationEventPublisherTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void shouldSendEnvelopeWithEventType() throws Exception {
        RegistrationEventPublisher publisher = new RegistrationEventPublisher(kafkaTemplate, new ObjectMapper());
        com.registration.entity.Registration reg = new com.registration.entity.Registration();
        reg.setPatientId(7L);
        reg.setRegistrationNo("GH1-3-x");
        reg.setScheduleDate(java.time.LocalDate.of(2026, 10, 20));
        reg.setPeriod(1);

        publisher.publishExpired(reg);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("ai.registration.event"), anyString(), json.capture());
        assertTrue(json.getValue().contains("APPOINTMENT_EXPIRED"));
        // 负载作为字符串字段嵌入信封，JSON 转义后内层引号带反斜杠
        assertTrue(json.getValue().contains("\\\"patientId\\\":7"));
    }

    @Test
    void shouldSkipWhenPatientIdNull() {
        RegistrationEventPublisher publisher = new RegistrationEventPublisher(kafkaTemplate, new ObjectMapper());
        com.registration.entity.Registration reg = new com.registration.entity.Registration();

        publisher.publishExpired(reg);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void shouldNotThrowWhenKafkaDown() {
        RegistrationEventPublisher publisher = new RegistrationEventPublisher(kafkaTemplate, new ObjectMapper());
        com.registration.entity.Registration reg = new com.registration.entity.Registration();
        reg.setPatientId(7L);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("kafka down"));

        assertDoesNotThrow(() -> publisher.publishExpired(reg));
    }
}
