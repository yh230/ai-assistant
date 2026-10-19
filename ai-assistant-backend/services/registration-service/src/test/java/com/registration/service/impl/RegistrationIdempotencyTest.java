package com.registration.service.impl;

import com.api.client.OrgClient;
import com.api.client.PatientClient;
import com.api.client.UserClient;
import com.api.dto.AuthInfoDTO;
import com.api.dto.OrgBriefDTO;
import com.api.dto.UserBriefDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.common.exception.BusinessException;
import com.common.result.Result;
import com.common.security.DataScopeResolver;
import com.registration.dto.AppointmentCreateDTO;
import com.registration.entity.DoctorSchedule;
import com.registration.entity.NumberSource;
import com.registration.entity.Registration;
import com.registration.enums.RegistrationStatus;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.mapper.RegistrationMapper;
import com.registration.mq.RegistrationEventPublisher;
import com.registration.vo.RegistrationVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匿名预约幂等单测（Redis 键占位 + 锁内重复校验 + 唯一键兜底 + 取消/过期键回收）
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class RegistrationIdempotencyTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private static final int PERIOD = 1;
    private static final String PHONE = "13800000000";
    private static final String ID_CARD = "110101199001011234";

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
    private PatientClient patientClient;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private TransactionTemplate txTemplate;
    @Mock
    private RegistrationEventPublisher eventPublisher;

    @InjectMocks
    private RegistrationServiceImpl registrationService;

    @BeforeEach
    void setUp() {
        // 初始化 MP TableInfo 缓存，使单测中的 LambdaQuery/UpdateWrapper 列解析可用
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Registration.class);
        TableInfoHelper.initTableInfo(assistant, NumberSource.class);
        TableInfoHelper.initTableInfo(assistant, DoctorSchedule.class);
    }

    /**
     * Redis 键已被占用且能查到原单：直接返回原单，不再获取排班锁、不落库
     */
    @Test
    void duplicateAnonymousSubmitReturnsExisting() {
        stubSchedule();
        RBucket<String> bucket = stubBucket(false);
        Registration existing = existingRegistration(99L);
        when(registrationMapper.selectOne(any())).thenReturn(existing);

        RegistrationVO vo = registrationService.createAppointment(anonymousDto());

        assertEquals(99L, vo.getId());
        verify(registrationMapper, never()).insert(any(Registration.class));
        verify(redissonClient, never()).getLock(anyString());
        verify(bucket, never()).delete();
    }

    /**
     * Redis 键已被占用但原单不可查（已取消/过期清理）：提示「请勿重复提交」
     */
    @Test
    void duplicateAnonymousSubmitWithoutExistingThrows() {
        stubSchedule();
        stubBucket(false);
        when(registrationMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> registrationService.createAppointment(anonymousDto()));
        assertEquals("请勿重复提交", ex.getMessage());
        verify(registrationMapper, never()).insert(any(Registration.class));
    }

    /**
     * 首次匿名提交：写入 64 位 SHA-256 幂等键，成功后键保留（5 分钟窗口）
     */
    @Test
    void anonymousCreateStoresIdempotencyKey() throws InterruptedException {
        DoctorSchedule schedule = stubSchedule();
        RBucket<String> bucket = stubBucket(true);
        stubLockAcquired();
        stubTx();
        stubFreeSource();
        stubEnrich();
        when(registrationMapper.selectCount(any())).thenReturn(0L);

        RegistrationVO vo = registrationService.createAppointment(anonymousDto());

        assertNotNull(vo);
        ArgumentCaptor<Registration> captor = ArgumentCaptor.forClass(Registration.class);
        verify(registrationMapper).insert(captor.capture());
        String raw = PHONE + "|" + ID_CARD + "|" + schedule.getId() + "|" + DATE + "|" + PERIOD;
        String expected = DigestUtils.sha256DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, captor.getValue().getIdempotencyKey());
        assertEquals(64, captor.getValue().getIdempotencyKey().length());
        verify(bucket, never()).delete();
    }

    /**
     * 排班锁获取失败：释放 Redis 幂等键，允许患者立即重试
     */
    @Test
    void lockFailureReleasesIdempotencyKey() throws InterruptedException {
        stubSchedule();
        RBucket<String> bucket = stubBucket(true);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> registrationService.createAppointment(anonymousDto()));
        assertEquals("当前操作繁忙，请稍后重试", ex.getMessage());
        verify(bucket).delete();
        verify(registrationMapper, never()).insert(any(Registration.class));
    }

    /**
     * 锁内命中同手机号+身份证+日期的进行中预约：拒绝并释放 Redis 幂等键
     */
    @Test
    void inLockDuplicateRejectedAndReleasesKey() throws InterruptedException {
        stubSchedule();
        RBucket<String> bucket = stubBucket(true);
        stubLockAcquired();
        stubTx();
        when(registrationMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> registrationService.createAppointment(anonymousDto()));
        assertEquals("您在该时段已有预约，请勿重复预约", ex.getMessage());
        verify(registrationMapper, never()).insert(any(Registration.class));
        verify(bucket).delete();
    }

    /**
     * 并发穿透触发唯一索引冲突：回查幂等键返回已落库记录，键保留在窗口内
     */
    @Test
    void duplicateKeyExceptionReturnsExisting() throws InterruptedException {
        stubSchedule();
        RBucket<String> bucket = stubBucket(true);
        stubLockAcquired();
        stubTx();
        // 号源挑选成功但插入即触发唯一键冲突（抢占更新不会执行）
        NumberSource source = new NumberSource();
        source.setId(5L);
        source.setSeqNo(1);
        when(numberSourceMapper.selectList(any())).thenReturn(List.of(source));
        when(registrationMapper.selectCount(any())).thenReturn(0L);
        when(registrationMapper.insert(any(Registration.class)))
                .thenThrow(new DuplicateKeyException("uk_registration_idem"));
        Registration existing = existingRegistration(88L);
        when(registrationMapper.selectOne(any())).thenReturn(existing);

        RegistrationVO vo = registrationService.createAppointment(anonymousDto());

        assertEquals(88L, vo.getId());
        verify(bucket, never()).delete();
    }

    /**
     * 患者账号预约路径不接触幂等键（仅匿名路径去重）
     */
    @Test
    void patientPathSkipsIdempotencyBucket() throws InterruptedException {
        stubSchedule();
        AuthInfoDTO auth = new AuthInfoDTO();
        auth.setHospitalId(100L);
        when(patientClient.authInfo(7L)).thenReturn(Result.success(auth));
        stubLockAcquired();
        stubTx();
        stubFreeSource();
        stubEnrich();
        when(registrationMapper.selectCount(any())).thenReturn(0L);

        AppointmentCreateDTO dto = anonymousDto();
        dto.setPatientId(7L);

        RegistrationVO vo = registrationService.createAppointment(dto);

        assertNotNull(vo);
        verify(redissonClient, never()).getBucket(anyString());
        verify(registrationMapper).insert(any(Registration.class));
    }

    /**
     * 取消预约：条件更新同时清空幂等键，患者可重新预约同一排班
     */
    @Test
    void cancelClearsIdempotencyKey() throws InterruptedException {
        Registration reg = existingRegistration(7L);
        reg.setNumberSourceId(5L);
        when(registrationMapper.selectById(7L)).thenReturn(reg);
        RBucket<String> bucket = stubIdemBucket();
        stubLockAcquired();
        stubTx();
        when(registrationMapper.update(isNull(), any())).thenReturn(1);
        when(numberSourceMapper.update(isNull(), any())).thenReturn(1);

        registrationService.patientCancel(7L, ID_CARD, "行程有变");

        assertTrue(updateSqlSet().toLowerCase().contains("idempotency"));
        verify(bucket).delete();
    }

    /**
     * 过期任务：条件跃迁同时清空幂等键，释放后可重新预约
     */
    @Test
    void expiryClearsIdempotencyKey() throws InterruptedException {
        Registration reg = existingRegistration(7L);
        reg.setNumberSourceId(5L);
        reg.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(registrationMapper.selectById(7L)).thenReturn(reg);
        RBucket<String> bucket = stubIdemBucket();
        stubLockAcquired();
        stubTx();
        when(registrationMapper.update(isNull(), any())).thenReturn(1);
        when(numberSourceMapper.update(isNull(), any())).thenReturn(1);

        boolean expired = registrationService.expireOverdueOne(7L);

        assertTrue(expired);
        assertTrue(updateSqlSet().toLowerCase().contains("idempotency"));
        verify(bucket).delete();
    }

    // ===================== 测试辅助 =====================

    private AppointmentCreateDTO anonymousDto() {
        AppointmentCreateDTO dto = new AppointmentCreateDTO();
        dto.setDoctorUserId(10L);
        dto.setScheduleDate(DATE);
        dto.setPeriod(PERIOD);
        dto.setPatientName("张三");
        dto.setPatientPhone(PHONE);
        dto.setPatientIdCard(ID_CARD);
        return dto;
    }

    private DoctorSchedule stubSchedule() {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setId(1L);
        schedule.setStatus(1);
        schedule.setHospitalId(100L);
        schedule.setDepartmentId(200L);
        schedule.setDoctorUserId(10L);
        schedule.setScheduleDate(DATE);
        schedule.setPeriod(PERIOD);
        when(scheduleMapper.selectOne(any())).thenReturn(schedule);
        return schedule;
    }

    private Registration existingRegistration(Long id) {
        Registration reg = new Registration();
        reg.setId(id);
        reg.setScheduleId(1L);
        reg.setScheduleDate(DATE);
        reg.setPeriod(PERIOD);
        reg.setPatientPhone(PHONE);
        reg.setPatientIdCard(ID_CARD);
        reg.setStatus(RegistrationStatus.RESERVED.getCode());
        reg.setIdempotencyKey("existing-key");
        return reg;
    }

    /** Redis 幂等键占位结果：true=首次提交，false=窗口内重复提交 */
    private RBucket<String> stubBucket(boolean firstSubmit) {
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        // 泛型方法 getBucket 用 doReturn 规避 when(...) 的类型推断问题
        doReturn(bucket).when(redissonClient).getBucket(anyString());
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(firstSubmit);
        return bucket;
    }

    /** 仅为键回收路径准备 bucket（不预设 trySet 结果） */
    @SuppressWarnings("unchecked")
    private RBucket<String> stubIdemBucket() {
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(redissonClient).getBucket(anyString());
        return bucket;
    }

    /** 所有锁均获取成功（同一 mock 实例按任意键返回） */
    private void stubLockAcquired() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    /** 事务模板 mock：直接执行事务体回调 */
    private void stubTx() {
        when(txTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
    }

    /** 提供一个空闲号源且条件抢占成功 */
    private void stubFreeSource() {
        NumberSource source = new NumberSource();
        source.setId(5L);
        source.setSeqNo(1);
        when(numberSourceMapper.selectList(any())).thenReturn(List.of(source));
        when(numberSourceMapper.update(isNull(), any())).thenReturn(1);
    }

    /** 跨中心名称回填返回空列表（避免 Feign 调用） */
    private void stubEnrich() {
        when(orgClient.hospitals(any())).thenReturn(Result.<List<OrgBriefDTO>>success(List.of()));
        when(orgClient.departments(any())).thenReturn(Result.<List<OrgBriefDTO>>success(List.of()));
        when(userClient.batch(any())).thenReturn(Result.<List<UserBriefDTO>>success(List.of()));
    }

    /** 捕获挂号记录条件更新包装器并返回其 SET 片段 */
    @SuppressWarnings("unchecked")
    private String updateSqlSet() {
        ArgumentCaptor<Wrapper<Registration>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(registrationMapper, atLeastOnce()).update(isNull(), captor.capture());
        return ((LambdaUpdateWrapper<Registration>) captor.getValue()).getSqlSet();
    }
}
