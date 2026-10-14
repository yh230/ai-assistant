package com.system.service.impl;

import com.api.dto.OperLogDTO;
import com.common.result.PageResult;
import com.system.entity.SysOperLog;
import com.system.mapper.SysOperLogMapper;
import com.system.vo.OperLogVO;
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
 * 操作日志服务测试
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class OperLogServiceImplTest {

    @Mock private SysOperLogMapper operLogMapper;
    @InjectMocks private OperLogServiceImpl operLogService;

    @Test
    void saveShouldMapAllFields() {
        OperLogDTO dto = new OperLogDTO();
        dto.setModule("挂号管理");
        dto.setAction("退号");
        dto.setUserId(1L);
        dto.setUsername("admin");
        dto.setIdentity("staff");
        dto.setMethod("POST");
        dto.setRequestUri("/api/registration/records/1/refund");
        dto.setParams("[{\"reason\":\"重复挂号\"}]");
        dto.setIp("127.0.0.1");
        dto.setStatus(0);
        dto.setErrorMsg("");
        dto.setCostMs(12L);

        operLogService.save(dto);

        ArgumentCaptor<SysOperLog> captor = ArgumentCaptor.forClass(SysOperLog.class);
        verify(operLogMapper).insert(captor.capture());
        SysOperLog entity = captor.getValue();
        assertEquals("挂号管理", entity.getModule());
        assertEquals("退号", entity.getAction());
        assertEquals(1L, entity.getUserId());
        assertEquals("admin", entity.getUsername());
        assertEquals("staff", entity.getIdentity());
        assertEquals(0, entity.getStatus());
        assertEquals(12L, entity.getCostMs());
        assertNotNull(entity.getOperTime());
    }

    @Test
    void pageShouldReturnMappedVoList() {
        SysOperLog entity = new SysOperLog();
        entity.setModule("用户管理");
        entity.setAction("新增用户");
        entity.setStatus(0);
        when(operLogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysOperLog> page = invocation.getArgument(0);
            page.setRecords(List.of(entity));
            page.setTotal(1);
            return page;
        });

        PageResult<OperLogVO> result = operLogService.page(null, null, null, null, null, 1, 10);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("用户管理", result.getList().get(0).getModule());
        assertEquals("新增用户", result.getList().get(0).getAction());
    }
}
