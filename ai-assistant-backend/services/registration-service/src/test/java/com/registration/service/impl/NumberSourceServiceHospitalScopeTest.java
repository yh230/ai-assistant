package com.registration.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.api.client.UserClient;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.registration.entity.DoctorSchedule;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 号源查询医院约束单测（内部调用传入 hospitalId 时按院过滤）
 *
 * @author yh
 * @date 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
class NumberSourceServiceHospitalScopeTest {

    @Mock
    private DoctorScheduleMapper scheduleMapper;
    @Mock
    private NumberSourceMapper numberSourceMapper;
    @Mock
    private UserClient userClient;
    @Mock
    private DataScopeResolver dataScopeResolver;

    @InjectMocks
    private NumberSourceServiceImpl numberSourceService;

    @BeforeEach
    void setUp() {
        // 初始化 MP TableInfo 缓存，使列名解析可用
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DoctorSchedule.class);
    }

    @Test
    void appliesHospitalFilterWhenProvided() {
        when(dataScopeResolver.resolve()).thenReturn(DataScope.all());
        when(scheduleMapper.selectList(any())).thenReturn(List.of());

        numberSourceService.listPools(1L, null, 100L, LocalDate.of(2026, 10, 20), null);

        ArgumentCaptor<Wrapper<DoctorSchedule>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(scheduleMapper).selectList(captor.capture());
        String sql = ((LambdaQueryWrapper<DoctorSchedule>) captor.getValue()).getSqlSegment();
        assertTrue(sql != null && sql.contains("hospital_id"), "应含医院过滤: " + sql);
    }

    @Test
    void skipsHospitalFilterWhenNull() {
        when(dataScopeResolver.resolve()).thenReturn(DataScope.all());
        when(scheduleMapper.selectList(any())).thenReturn(List.of());

        numberSourceService.listPools(null, null, 100L, LocalDate.of(2026, 10, 20), null);

        ArgumentCaptor<Wrapper<DoctorSchedule>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(scheduleMapper).selectList(captor.capture());
        String sql = ((LambdaQueryWrapper<DoctorSchedule>) captor.getValue()).getSqlSegment();
        assertFalse(sql != null && sql.contains("hospital_id"), "不应含医院过滤: " + sql);
    }
}
