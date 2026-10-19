package com.registration.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.api.client.UserClient;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.registration.entity.DoctorSchedule;
import com.registration.entity.NumberSource;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.support.ScheduleLock;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    @Mock
    private ScheduleLock scheduleLock;
    @Mock
    private TransactionTemplate txTemplate;

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

    /**
     * 号源再生：排班ID锁必须先于事务开启（锁覆盖事务提交）
     */
    @Test
    void generateForScheduleAcquiresLockBeforeTransaction() {
        DoctorSchedule s = new DoctorSchedule();
        s.setId(9L);
        s.setHospitalId(1L);
        s.setDepartmentId(20L);
        s.setDoctorUserId(200L);
        s.setScheduleDate(LocalDate.of(2026, 10, 20));
        s.setPeriod(1);
        s.setTotalLimit(2);
        when(scheduleLock.withLock(eq(9L), any(Supplier.class))).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(1)).get());
        when(txTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(numberSourceMapper.selectList(any())).thenReturn(List.of());
        when(numberSourceMapper.insert(any(NumberSource.class))).thenReturn(1);

        numberSourceService.generateForSchedule(s);

        InOrder inOrder = inOrder(scheduleLock, txTemplate);
        inOrder.verify(scheduleLock).withLock(eq(9L), any(Supplier.class));
        inOrder.verify(txTemplate).execute(any());
        verify(numberSourceMapper, times(2)).insert(any(NumberSource.class));
    }
}
