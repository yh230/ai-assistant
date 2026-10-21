package com.mcp.tool;

import com.api.client.OrgClient;
import com.api.client.RegistrationClient;
import com.api.dto.DoctorScheduleInfoDTO;
import com.api.dto.OrgBriefDTO;
import com.api.dto.ScheduleQueryResultDTO;
import com.common.result.Result;
import com.mcp.auth.McpKeyHasher;
import com.mcp.auth.McpPrincipal;
import com.mcp.auth.McpPrincipalContext;
import com.mcp.cache.ScheduleCache;
import com.mcp.support.McpMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MCP 排班工具测试
 *
 * @author yh
 * @date 2026-10-21
 */
@ExtendWith(MockitoExtension.class)
class ScheduleToolsTest {

    @Mock
    private RegistrationClient registrationClient;
    @Mock
    private OrgClient orgClient;
    @Mock
    private ScheduleCache scheduleCache;
    @Mock
    private McpKeyHasher keyHasher;
    @Mock
    private McpMetrics metrics;

    @InjectMocks
    private ScheduleTools tools;

    @BeforeEach
    void setUp() {
        McpPrincipalContext.set(new McpPrincipal("demo", List.of(1L), 60));
        lenient().when(scheduleCache.get(anyString())).thenReturn(Optional.empty());
        lenient().when(keyHasher.sha256(anyString())).thenReturn("cache-key");
    }

    @AfterEach
    void tearDown() {
        McpPrincipalContext.clear();
    }

    @Test
    void defaultsSingleHospitalAndReturnsItems() {
        ScheduleQueryResultDTO data = dataWithOneItem();
        when(registrationClient.querySchedules(
                1L, "2099-01-01", "2099-01-02", null, null, null))
                .thenReturn(Result.success(data));

        Object result = tools.queryDoctorSchedules(null, "2099-01-01", "2099-01-02", null, null, null);

        Map<?, ?> payload = (Map<?, ?>) result;
        assertEquals(1, ((List<?>) payload.get("items")).size());
        assertEquals("2099-01-01", payload.get("dateFrom"));
        verify(scheduleCache).put("mcp:schedule:cache-key", data);
    }

    @Test
    void usesTodayAndSevenDayWindowWhenDatesMissing() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        when(registrationClient.querySchedules(
                eq(1L), eq(today.toString()), eq(today.plusDays(6).toString()),
                isNull(), isNull(), isNull()))
                .thenReturn(Result.success(dataWithOneItem()));

        tools.queryDoctorSchedules(null, null, null, null, null, null);

        verify(registrationClient).querySchedules(
                eq(1L), eq(today.toString()), eq(today.plusDays(6).toString()),
                isNull(), isNull(), isNull());
    }

    @Test
    void requiresHospitalForMultiHospitalKey() {
        McpPrincipalContext.set(new McpPrincipal("demo", List.of(1L, 2L), 60));

        Object result = tools.queryDoctorSchedules(null, "2099-01-01", "2099-01-02", null, null, null);

        assertTrue(((Map<?, ?>) result).get("error").toString().contains("hospital_id"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void rejectsUnauthorizedHospital() {
        Object result = tools.queryDoctorSchedules(2L, "2099-01-01", "2099-01-02", null, null, null);

        assertTrue(((Map<?, ?>) result).get("error").toString().contains("无权查询该医院"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void rejectsWindowLongerThanFourteenDays() {
        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-20", null, null, null);

        assertTrue(((Map<?, ?>) result).get("error").toString().contains("14"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void mapsDownstreamFailureToError() {
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.fail("该医院不存在"));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null);

        assertEquals("该医院不存在", ((Map<?, ?>) result).get("error"));
    }

    @Test
    void successWithoutDataMapsToFallbackError() {
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.success(null));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null);

        assertEquals("排班查询服务暂时不可用，请稍后重试", ((Map<?, ?>) result).get("error"));
    }

    @Test
    void failureWithoutMessageMapsToFallbackError() {
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.fail(null));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null);

        assertEquals("排班查询服务暂时不可用，请稍后重试", ((Map<?, ?>) result).get("error"));
    }

    @Test
    void nullItemsExposedAsEmptyList() {
        ScheduleQueryResultDTO data = dataWithOneItem();
        data.setItems(null);
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.success(data));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null);

        Object items = ((Map<?, ?>) result).get("items");
        assertNotNull(items);
        assertEquals(0, ((List<?>) items).size());
    }

    @Test
    void returnsCachedResultWithoutCallingDownstream() {
        when(scheduleCache.get(anyString())).thenReturn(Optional.of(dataWithOneItem()));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null);

        assertEquals(1, ((List<?>) ((Map<?, ?>) result).get("items")).size());
        verifyNoInteractions(registrationClient);
    }

    @Test
    void listsOnlyHospitalsFromPrincipalScope() {
        OrgBriefDTO hospital = new OrgBriefDTO();
        hospital.setId(1L);
        hospital.setName("示例医院");
        when(orgClient.hospitals(List.of(1L))).thenReturn(Result.success(List.of(hospital)));

        Object result = tools.listHospitals();

        List<?> items = (List<?>) result;
        assertEquals(1, items.size());
        verify(orgClient).hospitals(List.of(1L));
    }

    private static ScheduleQueryResultDTO dataWithOneItem() {
        DoctorScheduleInfoDTO item = new DoctorScheduleInfoDTO();
        item.setHospitalId(1L);
        item.setHospitalName("示例医院");
        item.setDepartmentId(10L);
        item.setDepartmentName("内科");
        item.setDoctorUserId(100L);
        item.setDoctorName("张三");
        item.setScheduleDate("2099-01-01");
        item.setPeriod(1);
        item.setPeriodText("上午");
        item.setTotalLimit(20);
        item.setRemainingCount(7);
        ScheduleQueryResultDTO data = new ScheduleQueryResultDTO();
        data.setItems(List.of(item));
        data.setTruncated(false);
        return data;
    }
}
