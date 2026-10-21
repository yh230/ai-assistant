package com.registration.service.impl;

import com.api.client.OrgClient;
import com.api.client.UserClient;
import com.api.dto.OrgBriefDTO;
import com.api.dto.ScheduleQueryResultDTO;
import com.api.dto.UserBriefDTO;
import com.common.exception.BusinessException;
import com.common.result.Result;
import com.common.security.DataScopeResolver;
import com.registration.entity.DoctorSchedule;
import com.registration.entity.NumberSource;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.mapper.RegistrationMapper;
import com.registration.mq.RegistrationEventPublisher;
import com.registration.service.NumberSourceService;
import com.registration.support.ScheduleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 对外排班查询单测
 *
 * @author yh
 * @date 2026-10-21
 */
@ExtendWith(MockitoExtension.class)
class DoctorScheduleExternalQueryTest {

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
    private DoctorScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(orgClient.hospitals(anyList()))
                .thenReturn(Result.success(List.of(org(1L, "示例医院"))));
        lenient().when(orgClient.departments(anyList()))
                .thenReturn(Result.success(List.of(org(10L, "内科"))));
        lenient().when(userClient.batch(anyList()))
                .thenReturn(Result.success(List.of(user(100L, "张三"), user(101L, "李四"))));
    }

    @Test
    void countsOnlyFreeSourcesForRemaining() {
        when(scheduleMapper.selectList(any()))
                .thenReturn(List.of(schedule(1L, 100L), schedule(2L, 101L)));
        when(numberSourceMapper.selectList(any()))
                .thenReturn(List.of(freeSource(1L), freeSource(1L), freeSource(2L)));

        ScheduleQueryResultDTO result = service.queryForExternal(
                1L, "2099-01-01", "2099-01-01", null, null, null);

        assertEquals(2, result.getItems().size());
        assertEquals(2, result.getItems().get(0).getRemainingCount());
        assertEquals(1, result.getItems().get(1).getRemainingCount());
    }

    @Test
    void filtersByDoctorNameIgnoringCaseAndSpaces() {
        when(scheduleMapper.selectList(any()))
                .thenReturn(List.of(schedule(1L, 100L), schedule(2L, 101L)));
        when(numberSourceMapper.selectList(any())).thenReturn(List.of());

        ScheduleQueryResultDTO result = service.queryForExternal(
                1L, "2099-01-01", "2099-01-01", null, null, " 张 ");

        assertEquals(1, result.getItems().size());
        assertEquals("张三", result.getItems().get(0).getDoctorName());
    }

    @Test
    void rejectsWindowLongerThanFourteenDays() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.queryForExternal(1L, "2099-01-01", "2099-01-20", null, null, null));

        assertTrue(ex.getMessage().contains("14"));
    }

    @Test
    void rejectsStartDateAfterEndDate() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.queryForExternal(1L, "2099-01-10", "2099-01-01", null, null, null));

        assertTrue(ex.getMessage().contains("开始日期不能晚于结束日期"));
    }

    @Test
    void rejectsInvalidPeriod() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.queryForExternal(1L, "2099-01-01", "2099-01-01", null, 3, null));

        assertTrue(ex.getMessage().contains("时段"));
    }

    @Test
    void rejectsMissingHospitalId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.queryForExternal(null, "2099-01-01", "2099-01-01", null, null, null));

        assertTrue(ex.getMessage().contains("医院"));
    }

    @Test
    void truncatesToHundredRows() {
        List<DoctorSchedule> schedules = IntStream.rangeClosed(1, 101)
                .mapToObj(i -> schedule((long) i, 100L))
                .toList();
        when(scheduleMapper.selectList(any())).thenReturn(schedules);
        when(numberSourceMapper.selectList(any())).thenReturn(List.of());

        ScheduleQueryResultDTO result = service.queryForExternal(
                1L, "2099-01-01", "2099-01-01", null, null, null);

        assertEquals(100, result.getItems().size());
        assertTrue(result.isTruncated());
    }

    private static DoctorSchedule schedule(Long id, Long doctorUserId) {
        DoctorSchedule schedule = new DoctorSchedule();
        schedule.setId(id);
        schedule.setHospitalId(1L);
        schedule.setDepartmentId(10L);
        schedule.setDoctorUserId(doctorUserId);
        schedule.setScheduleDate(LocalDate.of(2099, 1, 1));
        schedule.setPeriod(1);
        schedule.setTotalLimit(20);
        schedule.setStatus(1);
        return schedule;
    }

    private static NumberSource freeSource(Long scheduleId) {
        NumberSource source = new NumberSource();
        source.setScheduleId(scheduleId);
        source.setStatus(1);
        return source;
    }

    private static OrgBriefDTO org(Long id, String name) {
        OrgBriefDTO dto = new OrgBriefDTO();
        dto.setId(id);
        dto.setName(name);
        return dto;
    }

    private static UserBriefDTO user(Long id, String name) {
        UserBriefDTO dto = new UserBriefDTO();
        dto.setId(id);
        dto.setName(name);
        return dto;
    }
}
