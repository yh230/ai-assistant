package com.system.notification.service.impl;

import com.api.mq.RegistrationMqEvent;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.system.notification.entity.PatientNotification;
import com.system.notification.mapper.PatientNotificationMapper;
import com.system.notification.vo.PatientNotificationVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内信服务测试：事件文案渲染、字段映射与归属校验
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private PatientNotificationMapper notificationMapper;
    @InjectMocks private NotificationServiceImpl notificationService;

    private RegistrationMqEvent event(Long patientId, String doctor, String dept, String date, Integer period) {
        RegistrationMqEvent event = new RegistrationMqEvent();
        event.setPatientId(patientId);
        event.setRegistrationNo("GH1-3-x");
        event.setDoctorName(doctor);
        event.setDepartmentName(dept);
        event.setVisitDate(date);
        event.setPeriod(period);
        return event;
    }

    private PatientNotification captureInserted() {
        ArgumentCaptor<PatientNotification> captor = ArgumentCaptor.forClass(PatientNotification.class);
        verify(notificationMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void createFromEventShouldRenderCreatedMessage() {
        RegistrationMqEvent event = event(7L, "张医生", "骨科", "2026-10-20", 1);
        event.setHospitalId(3L);

        notificationService.createFromEvent(RegistrationMqEvent.APPOINTMENT_CREATED, event);

        PatientNotification saved = captureInserted();
        assertEquals(7L, saved.getPatientId());
        assertEquals(3L, saved.getHospitalId());
        assertEquals(RegistrationMqEvent.APPOINTMENT_CREATED, saved.getType());
        assertEquals("预约成功", saved.getTitle());
        assertEquals("预约成功：骨科 张医生 2026-10-20上午，请按时就诊", saved.getContent());
        assertEquals("GH1-3-x", saved.getBizNo());
        assertEquals(0, saved.getReadFlag());
    }

    @Test
    void createFromEventShouldRenderCancelledMessage() {
        notificationService.createFromEvent(RegistrationMqEvent.APPOINTMENT_CANCELLED,
                event(7L, "李医生", "内科", "2026-10-21", 2));

        PatientNotification saved = captureInserted();
        assertEquals("预约已取消", saved.getTitle());
        assertEquals("预约已取消：内科 李医生 2026-10-21下午", saved.getContent());
    }

    @Test
    void createFromEventShouldRenderRefundedMessage() {
        notificationService.createFromEvent(RegistrationMqEvent.REGISTRATION_REFUNDED,
                event(8L, "王医生", "外科", "2026-10-22", 1));

        PatientNotification saved = captureInserted();
        assertEquals("退号成功", saved.getTitle());
        assertTrue(saved.getContent().startsWith("退号成功："));
    }

    @Test
    void createFromEventShouldRenderExpiredMessage() {
        notificationService.createFromEvent(RegistrationMqEvent.APPOINTMENT_EXPIRED,
                event(9L, null, null, "2026-10-23", 2));

        PatientNotification saved = captureInserted();
        assertEquals("预约已失效", saved.getTitle());
        assertEquals("预约已失效：您在 2026-10-23下午 的预约因超时未支付已释放", saved.getContent());
    }

    @Test
    void createFromEventShouldRenderScheduleCancelledMessage() {
        notificationService.createFromEvent(RegistrationMqEvent.SCHEDULE_CANCELLED,
                event(10L, null, null, "2026-10-24", 1));

        PatientNotification saved = captureInserted();
        assertEquals("停诊通知", saved.getTitle());
        assertEquals("停诊通知：您预约的 2026-10-24上午 门诊已停诊，请及时取消预约或联系医院改约", saved.getContent());
    }

    @Test
    void createFromEventShouldFallbackPeriodWhenUnknown() {
        notificationService.createFromEvent(RegistrationMqEvent.APPOINTMENT_CREATED,
                event(7L, "张医生", "骨科", "2026-10-20", 9));

        PatientNotification saved = captureInserted();
        assertTrue(saved.getContent().contains("2026-10-20时段"));
    }

    @Test
    void createFromEventShouldOmitEmptyDoctorAndDepartment() {
        notificationService.createFromEvent(RegistrationMqEvent.APPOINTMENT_CREATED,
                event(7L, null, null, "2026-10-20", 1));

        PatientNotification saved = captureInserted();
        assertEquals("预约成功：2026-10-20上午，请按时就诊", saved.getContent());
        assertFalse(saved.getContent().contains("null"));
    }

    @Test
    void markReadShouldThrowWhenNotOwned() {
        when(notificationMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> notificationService.markRead(7L, 100L));
        assertEquals("通知不存在或无权操作", ex.getMessage());
    }

    @Test
    void markReadShouldPassWhenOwned() {
        when(notificationMapper.update(any(), any())).thenReturn(1);

        assertDoesNotThrow(() -> notificationService.markRead(7L, 100L));
        verify(notificationMapper).update(any(), any());
    }

    @Test
    void pageForPatientShouldReturnMappedVoList() {
        PatientNotification entity = new PatientNotification();
        entity.setId(1L);
        entity.setType(RegistrationMqEvent.APPOINTMENT_CREATED);
        entity.setTitle("预约成功");
        entity.setContent("预约成功：骨科 张医生 2026-10-20上午，请按时就诊");
        entity.setReadFlag(0);
        when(notificationMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<PatientNotification> page = invocation.getArgument(0);
            page.setRecords(List.of(entity));
            page.setTotal(1);
            return page;
        });

        PageResult<PatientNotificationVO> result = notificationService.pageForPatient(7L, 1, 10);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("预约成功", result.getList().get(0).getTitle());
    }
}
