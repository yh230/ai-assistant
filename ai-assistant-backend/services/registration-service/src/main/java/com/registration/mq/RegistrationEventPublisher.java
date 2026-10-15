package com.registration.mq;

import com.api.mq.MqEnvelope;
import com.api.mq.MqTopics;
import com.api.mq.RegistrationMqEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.registration.entity.Registration;
import com.registration.vo.RegistrationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * 预约域事件发布器（事务提交后发送，避免回滚后误发；失败仅告警，匿名预约跳过）
 * <p>统一以 {@link MqEnvelope} 信封封装事件负载发往 {@link MqTopics#REGISTRATION_EVENT}：
 * 处于事务中时注册 afterCommit 于提交后发送，无事务时立即发送。</p>
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布「预约创建」事件（患者账号为空=匿名预约，跳过不发送）
     *
     * @param reg 挂号记录
     * @param vo 挂号视图（提供医生/科室名称，可空）
     */
    public void publishCreated(Registration reg, RegistrationVO vo) {
        send(RegistrationMqEvent.APPOINTMENT_CREATED, build(reg, vo, null));
    }

    /**
     * 发布「预约取消」事件（患者账号为空则跳过）
     *
     * @param reg 挂号记录
     * @param vo 挂号视图（提供医生/科室名称，可空）
     */
    public void publishCancelled(Registration reg, RegistrationVO vo) {
        send(RegistrationMqEvent.APPOINTMENT_CANCELLED, build(reg, vo, null));
    }

    /**
     * 发布「退号成功」事件（患者账号为空则跳过）
     *
     * @param reg 挂号记录
     * @param vo 挂号视图（提供医生/科室名称，可空）
     */
    public void publishRefunded(Registration reg, RegistrationVO vo) {
        send(RegistrationMqEvent.REGISTRATION_REFUNDED, build(reg, vo, null));
    }

    /**
     * 发布「预约过期失效」事件（无名称字段，患者账号为空则跳过）
     *
     * @param reg 挂号记录
     */
    public void publishExpired(Registration reg) {
        send(RegistrationMqEvent.APPOINTMENT_EXPIRED, build(reg, null, null));
    }

    /**
     * 发布「停诊通知」事件（对该排班受影响患者逐条发送，匿名预约自动跳过）
     *
     * @param schedule 被停用的排班
     * @param affected 受影响（已预约/已取号）的挂号记录
     */
    public void publishScheduleCancelled(com.registration.entity.DoctorSchedule schedule, List<Registration> affected) {
        for (Registration reg : affected) {
            send(RegistrationMqEvent.SCHEDULE_CANCELLED, build(reg, null, schedule == null ? null : schedule.getRemark()));
        }
    }

    /**
     * 组装事件负载（患者/单号/医院/日期/时段取自挂号单，名称取自视图，均可空）
     *
     * @param reg 挂号记录
     * @param vo 挂号视图（提供医生/科室名称，可空）
     * @param reason 事由（可空）
     * @return 事件负载
     */
    private RegistrationMqEvent build(Registration reg, RegistrationVO vo, String reason) {
        RegistrationMqEvent event = new RegistrationMqEvent();
        event.setPatientId(reg.getPatientId());
        event.setHospitalId(reg.getHospitalId());
        event.setRegistrationNo(reg.getRegistrationNo());
        event.setVisitDate(reg.getScheduleDate() == null ? null : reg.getScheduleDate().toString());
        event.setPeriod(reg.getPeriod());
        event.setDoctorName(vo == null ? null : vo.getDoctorName());
        event.setDepartmentName(vo == null ? null : vo.getDepartmentName());
        event.setReason(reason);
        return event;
    }

    /**
     * 发送事件（匿名预约跳过；处于事务中则注册 afterCommit 于提交后发送，否则立即发送）
     *
     * @param eventType 事件类型码
     * @param event 事件负载
     */
    private void send(String eventType, RegistrationMqEvent event) {
        if (event == null || event.getPatientId() == null) {
            return;
        }
        Runnable task = () -> doSend(eventType, event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /**
     * 信封封装并以 JSON 发送（best-effort，失败仅告警不影响业务）
     *
     * @param eventType 事件类型码
     * @param event 事件负载
     */
    private void doSend(String eventType, RegistrationMqEvent event) {
        try {
            MqEnvelope envelope = new MqEnvelope();
            envelope.setEventId(UUID.randomUUID().toString());
            envelope.setEventType(eventType);
            envelope.setOccurredAt(System.currentTimeMillis());
            envelope.setPayload(objectMapper.writeValueAsString(event));
            kafkaTemplate.send(MqTopics.REGISTRATION_EVENT, envelope.getEventId(),
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("预约域事件发布失败: type={}, reason={}", eventType, e.getMessage());
        }
    }
}
