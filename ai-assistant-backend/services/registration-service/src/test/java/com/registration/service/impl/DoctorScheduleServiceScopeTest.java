package com.registration.service.impl;

import com.api.client.OrgClient;
import com.api.client.UserClient;
import com.api.dto.UserBriefDTO;
import com.common.exception.BusinessException;
import com.common.result.Result;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.registration.dto.ScheduleSaveDTO;
import com.registration.entity.DoctorSchedule;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.mapper.RegistrationMapper;
import com.registration.mq.RegistrationEventPublisher;
import com.registration.service.NumberSourceService;
import com.registration.support.ScheduleLock;
import com.registration.vo.ScheduleVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
    @Mock
    private ScheduleLock scheduleLock;
    @Mock
    private TransactionTemplate txTemplate;

    @InjectMocks
    private DoctorScheduleServiceImpl doctorScheduleService;

    private static DoctorSchedule schedule(Long id, Long hospitalId) {
        DoctorSchedule s = new DoctorSchedule();
        s.setId(id);
        s.setHospitalId(hospitalId);
        s.setDepartmentId(20L);
        s.setDoctorUserId(200L);
        s.setStatus(1);
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

    /**
     * 保存：号源槽锁必须先于事务开启（锁覆盖事务提交），插入与号源再生在同一事务内完成
     */
    @Test
    void saveAcquiresSlotLockBeforeTransaction() {
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.hospital(1L));
        UserBriefDTO doctor = doctor();
        when(userClient.batch(anyList())).thenReturn(Result.success(List.of(doctor)));
        when(scheduleMapper.insert(any(DoctorSchedule.class))).thenAnswer(inv -> {
            ((DoctorSchedule) inv.getArgument(0)).setId(11L);
            return 1;
        });
        when(scheduleLock.withLock(any(String.class), any(Supplier.class))).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(1)).get());
        stubTx();
        stubEnrich();

        ScheduleVO vo = doctorScheduleService.save(dto(200L, LocalDate.of(2026, 10, 20), 1, 10));

        assertEquals(11L, vo.getId());
        verify(numberSourceService).generateForSchedule(any(DoctorSchedule.class));
        String slotKey = "lock:schedule-slot:200:2026-10-20:1";
        InOrder inOrder = inOrder(scheduleLock, txTemplate);
        inOrder.verify(scheduleLock).withLock(eq(slotKey), any(Supplier.class));
        inOrder.verify(txTemplate).execute(any());
    }

    /**
     * 更新：排班ID锁必须先于事务开启，事务提交后锁才由外层释放
     */
    @Test
    void updateAcquiresScheduleLockBeforeTransaction() {
        DoctorSchedule existing = schedule(5L, 1L);
        existing.setScheduleDate(LocalDate.of(2026, 10, 20));
        existing.setPeriod(1);
        existing.setTotalLimit(10);
        when(scheduleMapper.selectById(5L)).thenReturn(existing);
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.hospital(1L));
        UserBriefDTO doctor = doctor();
        when(userClient.batch(anyList())).thenReturn(Result.success(List.of(doctor)));
        when(scheduleLock.withLock(eq(5L), any(Supplier.class))).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(1)).get());
        stubTx();
        stubEnrich();

        ScheduleVO vo = doctorScheduleService.update(5L, dto(200L, LocalDate.of(2026, 10, 20), 1, 10));

        assertEquals(5L, vo.getId());
        InOrder inOrder = inOrder(scheduleLock, txTemplate);
        inOrder.verify(scheduleLock).withLock(eq(5L), any(Supplier.class));
        inOrder.verify(txTemplate).execute(any());
        verify(scheduleMapper).updateById(existing);
    }

    private static UserBriefDTO doctor() {
        UserBriefDTO doctor = new UserBriefDTO();
        doctor.setId(200L);
        doctor.setHospitalId(1L);
        doctor.setDepartmentId(20L);
        doctor.setName("张医生");
        return doctor;
    }

    private static ScheduleSaveDTO dto(Long doctorUserId, LocalDate date, Integer period, Integer totalLimit) {
        ScheduleSaveDTO dto = new ScheduleSaveDTO();
        dto.setDoctorUserId(doctorUserId);
        dto.setScheduleDate(date);
        dto.setPeriod(period);
        dto.setTotalLimit(totalLimit);
        return dto;
    }

    /** 事务模板 mock：直接执行事务体回调 */
    private void stubTx() {
        when(txTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
    }

    private void stubEnrich() {
        when(orgClient.hospitals(anyList())).thenReturn(Result.success(List.of()));
        when(orgClient.departments(anyList())).thenReturn(Result.success(List.of()));
    }
}
