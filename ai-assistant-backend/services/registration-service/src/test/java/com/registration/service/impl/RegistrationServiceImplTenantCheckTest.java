package com.registration.service.impl;

import com.api.client.PatientClient;
import com.api.dto.AuthInfoDTO;
import com.common.exception.BusinessException;
import com.common.result.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 写工具租户强校验单测（排班医院须与患者医院一致）
 *
 * @author yh
 * @date 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceImplTenantCheckTest {

    @Mock
    private PatientClient patientClient;

    @InjectMocks
    private RegistrationServiceImpl registrationService;

    /** 构造成功返回的患者归属信息 */
    private static Result<AuthInfoDTO> ok(Long hospitalId) {
        AuthInfoDTO dto = new AuthInfoDTO();
        dto.setHospitalId(hospitalId);
        return Result.success(dto);
    }

    @Test
    void rejectsWhenScheduleHospitalDiffers() {
        when(patientClient.authInfo(7L)).thenReturn(ok(2L));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> registrationService.assertPatientInScheduleHospital(7L, 1L));
        assertEquals("该医生不属于您所属医院，无法预约", ex.getMessage());
    }

    @Test
    void allowsWhenSameHospital() {
        when(patientClient.authInfo(7L)).thenReturn(ok(1L));
        assertDoesNotThrow(() -> registrationService.assertPatientInScheduleHospital(7L, 1L));
    }

    @Test
    void rejectsWhenPatientHospitalUnknown() {
        when(patientClient.authInfo(7L)).thenReturn(ok(null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> registrationService.assertPatientInScheduleHospital(7L, 1L));
        assertEquals("无法确认患者归属医院，请稍后重试", ex.getMessage());
    }

    @Test
    void rejectsWhenAuthInfoCallFails() {
        when(patientClient.authInfo(7L)).thenThrow(new RuntimeException("feign down"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> registrationService.assertPatientInScheduleHospital(7L, 1L));
        assertEquals("无法确认患者归属医院，请稍后重试", ex.getMessage());
    }

    @Test
    void skipsCheckForAnonymous() {
        assertDoesNotThrow(() -> registrationService.assertPatientInScheduleHospital(null, 1L));
        verify(patientClient, never()).authInfo(any());
    }
}
