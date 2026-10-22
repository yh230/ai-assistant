package com.registration.service.impl;

import com.api.client.OrgClient;
import com.api.client.UserClient;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.common.exception.BusinessException;
import com.common.security.DataScopeResolver;
import com.registration.dto.AppointmentCreateDTO;
import com.registration.dto.WalkInRegisterDTO;
import com.registration.entity.DoctorSchedule;
import com.registration.entity.NumberSource;
import com.registration.entity.Registration;
import com.registration.enums.PayStatus;
import com.registration.enums.RegistrationStatus;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.mapper.RegistrationMapper;
import com.registration.vo.RegistrationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂号核心服务并发控制分支单测（Redisson 锁拿不到 → 繁忙；条件更新 0 行 → 状态已变更）
 *
 * @author yh
 * @date 2026-10-14
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceImplConcurrencyTest {

    @Mock
    private RegistrationMapper registrationMapper;
    @Mock
    private NumberSourceMapper numberSourceMapper;
    @Mock
    private DoctorScheduleMapper scheduleMapper;
    @Mock
    private UserClient userClient;
    @Mock
    private OrgClient orgClient;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private TransactionTemplate txTemplate;

    @InjectMocks
    private RegistrationServiceImpl registrationService;

    /** 按锁键缓存 mock 锁实例，保证 getLock 同键返回同对象便于按用例 stub */
    private final Map<String, RLock> lockByKey = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        when(redissonClient.getLock(anyString())).thenAnswer(inv ->
                lockByKey.computeIfAbsent(inv.getArgument(0), k -> mock(RLock.class)));
        // 初始化 MP TableInfo 缓存，使单测中的 LambdaUpdateWrapper 列解析可用
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Registration.class);
        TableInfoHelper.initTableInfo(assistant, NumberSource.class);
        TableInfoHelper.initTableInfo(assistant, DoctorSchedule.class);
    }

    /**
     * 排班锁被占：createAppointment 应快速失败抛出「当前操作繁忙」，不得落任何业务写入
     */
    @Test
    void createAppointmentFailsFastWhenScheduleLockBusy() throws InterruptedException {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setId(1L);
        schedule.setStatus(1);
        when(scheduleMapper.selectOne(any())).thenReturn(schedule);

        RLock scheduleLock = lockByKey.computeIfAbsent("lock:schedule:1", k -> mock(RLock.class));
        when(scheduleLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
        // 匿名路径新增幂等键占位：键写入成功后才进入加锁分支
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(redissonClient).getBucket(anyString());
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        AppointmentCreateDTO dto = new AppointmentCreateDTO();
        dto.setDoctorUserId(10L);
        dto.setScheduleDate(LocalDate.of(2026, 10, 15));
        dto.setPeriod(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> registrationService.createAppointment(dto));
        assertEquals("当前操作繁忙，请稍后重试", ex.getMessage());
        verify(registrationMapper, never()).insert(any(Registration.class));
    }

    /**
     * 取号条件更新 0 行（状态已被并发变更）：应抛「状态已变更，请刷新后重试」并释放锁
     */
    @Test
    void takeFailsWhenConditionalTransitAffectsZeroRows() throws InterruptedException {
        Registration reg = new Registration();
        reg.setId(7L);
        reg.setStatus(RegistrationStatus.RESERVED.getCode());
        reg.setPayStatus(PayStatus.UNPAID.getCode());
        when(registrationMapper.selectById(7L)).thenReturn(reg);

        RLock registrationLock = lockByKey.computeIfAbsent("lock:registration:7", k -> mock(RLock.class));
        when(registrationLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(registrationLock.isHeldByCurrentThread()).thenReturn(true);
        when(registrationMapper.update(isNull(), any())).thenReturn(0);
        // 事务模板 mock：直接执行事务体回调
        when(txTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));

        BusinessException ex = assertThrows(BusinessException.class, () -> registrationService.take(7L));
        assertEquals("状态已变更，请刷新后重试", ex.getMessage());
        verify(registrationLock).unlock();
    }

    /**
     * walkIn 名称回填（跨中心 Feign）抛异常：应降级返回名称缺省的成功视图，不得抛出异常
     */
    @Test
    void walkInReturnsDegradedVoWhenEnrichBackfillFails() throws InterruptedException {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setId(1L);
        schedule.setStatus(1);
        schedule.setHospitalId(100L);
        schedule.setDepartmentId(200L);
        schedule.setDoctorUserId(10L);
        schedule.setScheduleDate(LocalDate.of(2026, 10, 15));
        schedule.setPeriod(1);
        when(scheduleMapper.selectOne(any())).thenReturn(schedule);

        NumberSource source = new NumberSource();
        source.setId(5L);
        source.setSeqNo(1);
        when(numberSourceMapper.selectList(any())).thenReturn(List.of(source));
        when(numberSourceMapper.update(isNull(), any())).thenReturn(1);

        RLock scheduleLock = lockByKey.computeIfAbsent("lock:schedule:1", k -> mock(RLock.class));
        when(scheduleLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(scheduleLock.isHeldByCurrentThread()).thenReturn(true);
        when(txTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(orgClient.hospitals(any())).thenThrow(new RuntimeException("医院中心不可用"));

        WalkInRegisterDTO dto = new WalkInRegisterDTO();
        dto.setDoctorUserId(10L);
        dto.setScheduleDate(LocalDate.of(2026, 10, 15));
        dto.setPeriod(1);
        dto.setPatientName("张三");

        RegistrationVO vo = registrationService.walkIn(dto);

        assertNotNull(vo);
        assertEquals(RegistrationStatus.CALLED.getCode(), vo.getStatus());
        assertEquals(100L, vo.getHospitalId());
        assertEquals(10L, vo.getDoctorUserId());
        assertNull(vo.getHospitalName());
        assertNull(vo.getDepartmentName());
        assertNull(vo.getDoctorName());
        verify(scheduleLock).unlock();
    }
}
