package com.common.security.operlog;

import com.api.client.OperLogClient;
import com.api.dto.OperLogDTO;
import com.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 操作日志切面测试（上报成功不阻断、异常重抛、上报失败静默）
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class OperLogAspectTest {

    @Mock private OperLogClient operLogClient;
    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private OperLog operLog;

    private OperLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new OperLogAspect(operLogClient, new ObjectMapper());
    }

    @Test
    void shouldReportSuccessWithoutBlockingResult() throws Throwable {
        when(operLog.module()).thenReturn("患者预约");
        when(operLog.action()).thenReturn("线上预约");
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[]{Map.of("idCard", "110101199001011234")});

        Object result = aspect.around(joinPoint, operLog);

        assertEquals("ok", result);
        ArgumentCaptor<OperLogDTO> captor = ArgumentCaptor.forClass(OperLogDTO.class);
        verify(operLogClient).report(captor.capture());
        OperLogDTO dto = captor.getValue();
        assertEquals("患者预约", dto.getModule());
        assertEquals(0, dto.getStatus());
        assertEquals("", dto.getErrorMsg());
        assertNotNull(dto.getCostMs());
        assertTrue(dto.getParams().contains("\"idCard\":\"***\""));
        assertFalse(dto.getParams().contains("110101199001011234"));
    }

    @Test
    void shouldRethrowBusinessExceptionWithFailedStatus() throws Throwable {
        when(operLog.module()).thenReturn("挂号管理");
        when(operLog.action()).thenReturn("退号");
        when(joinPoint.proceed()).thenThrow(new BusinessException("状态已变更，请刷新后重试"));
        when(joinPoint.getArgs()).thenReturn(new Object[0]);

        BusinessException thrown = assertThrows(BusinessException.class, () -> aspect.around(joinPoint, operLog));

        assertEquals("状态已变更，请刷新后重试", thrown.getMessage());
        ArgumentCaptor<OperLogDTO> captor = ArgumentCaptor.forClass(OperLogDTO.class);
        verify(operLogClient).report(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals("状态已变更，请刷新后重试", captor.getValue().getErrorMsg());
    }

    @Test
    void shouldNotPropagateWhenReportFails() throws Throwable {
        when(operLog.module()).thenReturn("用户管理");
        when(operLog.action()).thenReturn("新增用户");
        when(joinPoint.proceed()).thenReturn("ok");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(operLogClient.report(any(OperLogDTO.class))).thenThrow(new RuntimeException("服务不可达"));

        assertEquals("ok", aspect.around(joinPoint, operLog));
    }
}
