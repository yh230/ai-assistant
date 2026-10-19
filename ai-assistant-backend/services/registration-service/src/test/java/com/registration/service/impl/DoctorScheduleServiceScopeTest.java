package com.registration.service.impl;

import com.api.client.OrgClient;
import com.api.client.UserClient;
import com.common.exception.BusinessException;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.registration.entity.DoctorSchedule;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.mapper.RegistrationMapper;
import com.registration.mq.RegistrationEventPublisher;
import com.registration.service.NumberSourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 排班号源重生成数据范围单测（跨院排班不得触发号源变更）
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class DoctorScheduleServiceScopeTest {

    @Mock
    private DoctorScheduleMapper scheduleMapper;
    @Mock
    private NumberSourceMapper numberSourceMapper;
    @Mock
    private NumberSourceService numberSourceService;
    @Mock
    private UserClient userClient;
    @Mock
    private OrgClient orgClient;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private RegistrationMapper registrationMapper;
    @Mock
    private RegistrationEventPublisher eventPublisher;

    @InjectMocks
    private DoctorScheduleServiceImpl doctorScheduleService;

    private static DoctorSchedule schedule(Long id, Long hospitalId) {
        DoctorSchedule s = new DoctorSchedule();
        s.setId(id);
        s.setHospitalId(hospitalId);
        s.setDepartmentId(20L);
        s.setDoctorUserId(200L);
        return s;
    }

    @Test
    void generateSourcesDeniesScheduleOutsideOperatorHospital() {
        DoctorSchedule otherHospital = schedule(2L, 2L);
        when(scheduleMapper.selectById(2L)).thenReturn(otherHospital);
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.hospital(1L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> doctorScheduleService.generateSources(2L));
        assertEquals("无权操作该排班", ex.getMessage());
        // 断言失败必须发生在任何号源变更之前
        verifyNoInteractions(numberSourceService);
    }

    @Test
    void generateSourcesAllowsScheduleInOperatorHospital() {
        DoctorSchedule ownHospital = schedule(3L, 1L);
        when(scheduleMapper.selectById(3L)).thenReturn(ownHospital);
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.hospital(1L));

        doctorScheduleService.generateSources(3L);

        verify(numberSourceService).generateForSchedule(ownHospital);
    }
}
