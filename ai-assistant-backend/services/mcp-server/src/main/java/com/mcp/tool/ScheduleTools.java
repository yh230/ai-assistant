package com.mcp.tool;

import com.api.client.OrgClient;
import com.api.client.RegistrationClient;
import com.api.dto.OrgBriefDTO;
import com.api.dto.ScheduleQueryResultDTO;
import com.common.result.Result;
import com.mcp.auth.McpKeyHasher;
import com.mcp.auth.McpPrincipal;
import com.mcp.auth.McpPrincipalContext;
import com.mcp.cache.ScheduleCache;
import com.mcp.support.McpMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 排班查询工具
 *
 * @author yh
 * @date 2026-10-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleTools {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_WINDOW_DAYS = 7;
    private static final int MAX_WINDOW_DAYS = 14;
    private static final String TOOL_QUERY = "query_doctor_schedules";
    private static final String TOOL_HOSPITALS = "list_hospitals";

    private final RegistrationClient registrationClient;
    private final OrgClient orgClient;
    private final ScheduleCache scheduleCache;
    private final McpKeyHasher keyHasher;
    private final McpMetrics metrics;

    /**
     * 查询当前 Key 可访问的医院
     *
     * @return 医院列表或错误对象
     */
    @Tool(name = TOOL_HOSPITALS, description = "查询当前 API Key 可访问的医院列表。无参数。")
    public Object listHospitals() {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            McpPrincipal principal = McpPrincipalContext.get();
            if (principal == null) {
                outcome = "error";
                return error("未认证的调用方");
            }
            Result<List<OrgBriefDTO>> result = orgClient.hospitals(principal.hospitalIds());
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                outcome = "error";
                return error("医院信息查询服务暂时不可用，请稍后重试");
            }
            return result.getData().stream()
                    .map(hospital -> new HospitalItem(hospital.getId(), hospital.getName()))
                    .toList();
        } catch (Exception e) {
            outcome = "error";
            log.warn("MCP 查询医院失败: {}", e.getMessage());
            return error("医院信息查询服务暂时不可用，请稍后重试");
        } finally {
            metrics.toolCall(TOOL_HOSPITALS, outcome, System.nanoTime() - start);
        }
    }

    /**
     * 查询指定医院未来一段时间的医生排班与余量
     *
     * @param hospitalId 医院 ID，单院 Key 可空
     * @param dateFrom 开始日期（yyyy-MM-dd），默认今天
     * @param dateTo 结束日期（yyyy-MM-dd），默认开始日期后 6 天
     * @param departmentId 科室 ID（可空）
     * @param doctorName 医生姓名关键字（可空）
     * @param period 时段（1=上午，2=下午，可空）
     * @return 查询结果或错误对象
     */
    @Tool(name = TOOL_QUERY, description = "查询某医院未来 14 天内医生排班与号源余量。参数：hospital_id=医院ID（单院 Key 可空），date_from=开始日期 yyyy-MM-dd，date_to=结束日期 yyyy-MM-dd（窗口最多14天），department_id=科室ID（可空），doctor_name=医生姓名（可空，包含匹配），period=1上午/2下午（可空）。")
    public Object queryDoctorSchedules(
            @ToolParam(description = "医院ID；Key 只绑定一家医院时可空", required = false) Long hospitalId,
            @ToolParam(description = "开始日期 yyyy-MM-dd，默认今天", required = false) String dateFrom,
            @ToolParam(description = "结束日期 yyyy-MM-dd，默认开始日期后 6 天，窗口最多 14 天", required = false) String dateTo,
            @ToolParam(description = "科室ID，可空", required = false) Long departmentId,
            @ToolParam(description = "医生姓名，包含匹配，可空", required = false) String doctorName,
            @ToolParam(description = "时段 1=上午 2=下午，可空", required = false) Integer period) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            McpPrincipal principal = McpPrincipalContext.get();
            if (principal == null) {
                outcome = "error";
                return error("未认证的调用方");
            }
            Long targetHospital = hospitalId;
            if (targetHospital == null) {
                if (principal.hospitalIds().size() != 1) {
                    outcome = "error";
                    return error("请指定 hospital_id");
                }
                targetHospital = principal.hospitalIds().get(0);
            }
            if (!principal.canAccess(targetHospital)) {
                outcome = "error";
                return error("无权查询该医院");
            }
            if (period != null && period != 1 && period != 2) {
                outcome = "error";
                return error("时段仅支持 1=上午、2=下午");
            }

            LocalDate from;
            LocalDate to;
            try {
                LocalDate today = LocalDate.now(ZONE);
                from = (dateFrom == null || dateFrom.isBlank()) ? today : LocalDate.parse(dateFrom.trim());
                if (from.isBefore(today)) {
                    from = today;
                }
                to = (dateTo == null || dateTo.isBlank())
                        ? from.plusDays(DEFAULT_WINDOW_DAYS - 1)
                        : LocalDate.parse(dateTo.trim());
            } catch (DateTimeParseException e) {
                outcome = "error";
                return error("日期格式应为 yyyy-MM-dd");
            }
            if (from.isAfter(to)) {
                outcome = "error";
                return error("开始日期不能晚于结束日期");
            }
            if (to.isAfter(from.plusDays(MAX_WINDOW_DAYS - 1))) {
                outcome = "error";
                return error("查询窗口最多 " + MAX_WINDOW_DAYS + " 天");
            }
            String keyword = doctorName == null || doctorName.isBlank() ? null : doctorName.trim();
            String cacheKey = cacheKey(targetHospital, from, to, departmentId, period, keyword);

            Optional<ScheduleQueryResultDTO> cached = scheduleCache.get(cacheKey);
            ScheduleQueryResultDTO data;
            if (cached.isPresent()) {
                data = cached.get();
            } else {
                Result<ScheduleQueryResultDTO> result = registrationClient.querySchedules(
                        targetHospital, from.toString(), to.toString(), departmentId, period, keyword);
                if (result == null || result.getCode() != 200 || result.getData() == null) {
                    outcome = "error";
                    return error(result == null ? "排班查询服务暂时不可用，请稍后重试" : result.getMsg());
                }
                data = result.getData();
                scheduleCache.put(cacheKey, data);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", data.getItems());
            payload.put("truncated", data.isTruncated());
            payload.put("dateFrom", from.toString());
            payload.put("dateTo", to.toString());
            log.info("MCP 排班查询 consumer={} hospitalId={} from={} to={} rows={} truncated={}",
                    principal.consumer(), targetHospital, from, to,
                    data.getItems() == null ? 0 : data.getItems().size(), data.isTruncated());
            return payload;
        } catch (Exception e) {
            outcome = "error";
            log.warn("MCP 排班查询失败: {}", e.getMessage());
            return error("排班查询服务暂时不可用，请稍后重试");
        } finally {
            metrics.toolCall(TOOL_QUERY, outcome, System.nanoTime() - start);
        }
    }

    /**
     * 构造缓存键
     *
     * @param hospitalId 医院 ID
     * @param from 开始日期
     * @param to 结束日期
     * @param departmentId 科室 ID
     * @param period 时段
     * @param doctorName 医生姓名
     * @return 缓存键
     */
    private String cacheKey(Long hospitalId, LocalDate from, LocalDate to,
                            Long departmentId, Integer period, String doctorName) {
        String canonical = hospitalId + "|" + from + "|" + to + "|"
                + (departmentId == null ? "" : departmentId) + "|"
                + (period == null ? "" : period) + "|"
                + (doctorName == null ? "" : doctorName);
        return "mcp:schedule:" + keyHasher.sha256(canonical);
    }

    /**
     * 构造错误返回
     *
     * @param message 错误文案
     * @return 错误对象
     */
    private static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    /**
     * 医院列表项
     *
     * @param hospitalId 医院 ID
     * @param hospitalName 医院名称
     */
    public record HospitalItem(Long hospitalId, String hospitalName) {
    }
}
