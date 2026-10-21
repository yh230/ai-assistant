package com.registration.service.impl;

import com.api.client.OrgClient;
import com.api.client.UserClient;
import com.api.dto.OrgBriefDTO;
import com.api.dto.ScheduleQueryResultDTO;
import com.api.dto.UserBriefDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
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
        // 初始化 MP TableInfo 缓存，使 LambdaQueryWrapper 列名解析可用
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DoctorSchedule.class);
        TableInfoHelper.initTableInfo(assistant, NumberSource.class);
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

        // 校验余量查询确实带 status=1 过滤，而非仅依赖 mock 返回值
        ArgumentCaptor<Wrapper<NumberSource>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(numberSourceMapper).selectList(captor.capture());
        LambdaQueryWrapper<NumberSource> wrapper =
                (LambdaQueryWrapper<NumberSource>) captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql != null && sql.contains("status"), "号源查询应带 status 过滤: " + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1),
                "号源查询 status 应绑定 1: " + wrapper.getParamNameValuePairs());
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
    void matchesLatinDoctorNameIgnoringCase() {
        when(scheduleMapper.selectList(any()))
                .thenReturn(List.of(schedule(1L, 100L), schedule(2L, 101L)));
        when(numberSourceMapper.selectList(any())).thenReturn(List.of());
        when(userClient.batch(anyList()))
                .thenReturn(Result.success(List.of(user(100L, "Li Si"), user(101L, "Wang Wu"))));

        ScheduleQueryResultDTO result = service.queryForExternal(
                1L, "2099-01-01", "2099-01-01", null, null, " li ");

        assertEquals(1, result.getItems().size());
        assertEquals("Li Si", result.getItems().get(0).getDoctorName());
    }

    @Test
    void trimsStoredDoctorNameBeforeMatching() {
        when(scheduleMapper.selectList(any()))
                .thenReturn(List.of(schedule(1L, 100L), schedule(2L, 101L)));
        when(numberSourceMapper.selectList(any())).thenReturn(List.of());
        when(userClient.batch(anyList()))
                .thenReturn(Result.success(List.of(user(100L, " 张三 "), user(101L, "李四"))));

        ScheduleQueryResultDTO result = service.queryForExternal(
                1L, "2099-01-01", "2099-01-01", null, null, "张");

        assertEquals(1, result.getItems().size());
        assertEquals(100L, result.getItems().get(0).getDoctorUserId());
    }

    @Test
    void rejectsExpiredDateWindow() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.queryForExternal(1L, "2020-01-01", "2020-01-02", null, null, null));

        assertEquals("查询区间已过期", ex.getMessage());
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

    @Test
    void marksTruncatedWhenScanHitsFiveHundredRows() {
        List<DoctorSchedule> schedules = IntStream.rangeClosed(1, 501)
                .mapToObj(i -> schedule((long) i, 100L))
                .toList();
        when(scheduleMapper.selectList(any())).thenReturn(schedules);
        when(numberSourceMapper.selectList(any())).thenReturn(List.of());

        ScheduleQueryResultDTO result = service.queryForExternal(
                1L, "2099-01-01", "2099-01-01", null, null, null);

        // 扫描上限 500：超出即置 truncated；返回仍受 100 条上限约束
        assertTrue(result.isTruncated());
        assertEquals(100, result.getItems().size());

        ArgumentCaptor<Wrapper<DoctorSchedule>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(scheduleMapper).selectList(captor.capture());
        String sql = ((LambdaQueryWrapper<DoctorSchedule>) captor.getValue()).getSqlSegment();
        assertTrue(sql != null && sql.trim().endsWith("LIMIT 501"), "扫描上限应为 501: " + sql);
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
