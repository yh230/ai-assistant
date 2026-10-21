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
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    private ToolContext context;

    @BeforeEach
    void setUp() {
        context = contextFor(new McpPrincipal("demo", List.of(1L), 60));
        lenient().when(scheduleCache.get(anyString())).thenReturn(Optional.empty());
        lenient().when(keyHasher.sha256(anyString())).thenReturn("cache-key");
    }

    @Test
    void defaultsSingleHospitalAndReturnsItems() {
        ScheduleQueryResultDTO data = dataWithOneItem();
        when(registrationClient.querySchedules(
                1L, "2099-01-01", "2099-01-02", null, null, null))
                .thenReturn(Result.success(data));

        Object result = tools.queryDoctorSchedules(null, "2099-01-01", "2099-01-02", null, null, null, context);

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

        tools.queryDoctorSchedules(null, null, null, null, null, null, context);

        verify(registrationClient).querySchedules(
                eq(1L), eq(today.toString()), eq(today.plusDays(6).toString()),
                isNull(), isNull(), isNull());
    }

    @Test
    void requiresHospitalForMultiHospitalKey() {
        context = contextFor(new McpPrincipal("demo", List.of(1L, 2L), 60));

        Object result = tools.queryDoctorSchedules(null, "2099-01-01", "2099-01-02", null, null, null, context);

        assertTrue(((Map<?, ?>) result).get("error").toString().contains("hospital_id"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void rejectsUnauthorizedHospital() {
        Object result = tools.queryDoctorSchedules(2L, "2099-01-01", "2099-01-02", null, null, null, context);

        assertTrue(((Map<?, ?>) result).get("error").toString().contains("无权查询该医院"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void rejectsWindowLongerThanFourteenDays() {
        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-20", null, null, null, context);

        assertTrue(((Map<?, ?>) result).get("error").toString().contains("14"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void rejectsUnauthenticatedCaller() {
        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null,
                new ToolContext(Map.of()));

        assertEquals("未认证的调用方", ((Map<?, ?>) result).get("error"));
        verifyNoInteractions(registrationClient);
    }

    @Test
    void mapsDownstreamFailureToGenericError() {
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.fail("该医院不存在"));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null, context);

        assertEquals("排班查询服务暂时不可用，请稍后重试", ((Map<?, ?>) result).get("error"));
    }

    @Test
    void doesNotExposeInternalDownstreamMessage() {
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.fail("内部细节: 数据库连接失败"));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null, context);

        String message = ((Map<?, ?>) result).get("error").toString();
        assertEquals("排班查询服务暂时不可用，请稍后重试", message);
        assertFalse(message.contains("数据库连接失败"));
    }

    @Test
    void successWithoutDataMapsToFallbackError() {
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.success(null));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null, context);

        assertEquals("排班查询服务暂时不可用，请稍后重试", ((Map<?, ?>) result).get("error"));
    }

    @Test
    void failureWithoutMessageMapsToFallbackError() {
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.fail(null));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null, context);

        assertEquals("排班查询服务暂时不可用，请稍后重试", ((Map<?, ?>) result).get("error"));
    }

    @Test
    void nullItemsExposedAsEmptyList() {
        ScheduleQueryResultDTO data = dataWithOneItem();
        data.setItems(null);
        when(registrationClient.querySchedules(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Result.success(data));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null, context);

        Object items = ((Map<?, ?>) result).get("items");
        assertNotNull(items);
        assertEquals(0, ((List<?>) items).size());
    }

    @Test
    void returnsCachedResultWithoutCallingDownstream() {
        when(scheduleCache.get(anyString())).thenReturn(Optional.of(dataWithOneItem()));

        Object result = tools.queryDoctorSchedules(1L, "2099-01-01", "2099-01-02", null, null, null, context);

        assertEquals(1, ((List<?>) ((Map<?, ?>) result).get("items")).size());
        verifyNoInteractions(registrationClient);
    }

    @Test
    void listsOnlyHospitalsFromPrincipalScope() {
        OrgBriefDTO allowed = hospital(1L, "示例医院");
        OrgBriefDTO other = hospital(2L, "其他医院");
        when(orgClient.hospitals(List.of(1L))).thenReturn(Result.success(List.of(allowed, other)));

        Object result = tools.listHospitals(context);

        List<?> items = (List<?>) result;
        assertEquals(1, items.size());
        assertEquals(1L, ((ScheduleTools.HospitalItem) items.get(0)).hospitalId());
        verify(orgClient).hospitals(List.of(1L));
    }

    @Test
    void rejectsUnauthenticatedHospitalList() {
        Object result = tools.listHospitals(new ToolContext(Map.of()));

        assertEquals("未认证的调用方", ((Map<?, ?>) result).get("error"));
        verifyNoInteractions(orgClient);
    }

    /**
     * 构造携带调用方的工具上下文
     *
     * @param principal 调用方
     * @return 工具上下文
     */
    private static ToolContext contextFor(McpPrincipal principal) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(McpPrincipalContext.TRANSPORT_KEY, principal)));
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }

    private static OrgBriefDTO hospital(Long id, String name) {
        OrgBriefDTO hospital = new OrgBriefDTO();
        hospital.setId(id);
        hospital.setName(name);
        return hospital;
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
