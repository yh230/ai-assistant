package com.hospital.service.impl;

import com.common.exception.BusinessException;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.hospital.entity.Hospital;
import com.hospital.mapper.DepartmentMapper;
import com.hospital.mapper.HospitalMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 医院按 id 读取的数据范围回归：院长仅可读取本院
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class HospitalServiceImplDataScopeTest {

    @Mock private HospitalMapper hospitalMapper;
    @Mock private DepartmentMapper departmentMapper;
    @Mock private DataScopeResolver dataScopeResolver;
    @InjectMocks private HospitalServiceImpl hospitalService;

    @Test
    void getRejectsHospitalOutOfScope() {
        // 当前登录人范围为医院 1，目标记录属于医院 2
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.hospital(1L));
        when(hospitalMapper.selectById(2L)).thenReturn(hospital(2L));

        BusinessException ex = assertThrows(BusinessException.class, () -> hospitalService.get(2L));
        assertEquals("无权查看该医院", ex.getMessage());
        verifyNoInteractions(departmentMapper);
    }

    @Test
    void getAllowsHospitalInScope() {
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.hospital(1L));
        when(hospitalMapper.selectById(1L)).thenReturn(hospital(1L));

        assertEquals(1L, hospitalService.get(1L).getId());
    }

    /**
     * 构造医院实体
     *
     * @param id 医院 ID
     * @return 医院实体
     */
    private static Hospital hospital(Long id) {
        Hospital h = new Hospital();
        h.setId(id);
        h.setName("医院" + id);
        return h;
    }
}
