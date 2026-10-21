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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
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
import java.util.concurrent.TimeUnit;

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
    private static final String UNAUTHENTICATED = "未认证的调用方";
    private static final String DOWNSTREAM_ERROR = "排班查询服务暂时不可用，请稍后重试";
    private static final String HOSPITAL_ERROR = "医院信息查询服务暂时不可用，请稍后重试";

    private final RegistrationClient registrationClient;
    private final OrgClient orgClient;
    private final ScheduleCache scheduleCache;
    private final McpKeyHasher keyHasher;
    private final McpMetrics metrics;

    /**
     * 查询当前 Key 可访问的医院
     *
     * @param toolContext Spring AI 工具上下文
     * @return 医院列表
     */
    @Tool(name = TOOL_HOSPITALS, description = "查询当前 API Key 可访问的医院列表。无参数。")
    public Object listHospitals(ToolContext toolContext) {
        long start = System.nanoTime();
        McpPrincipal principal = McpPrincipalContext.from(toolContext);
        if (principal == null) {
            log.warn("MCP 工具调用拒绝: tool={}, reason=unauthenticated", TOOL_HOSPITALS);
            recordMetrics(TOOL_HOSPITALS, "error", start);
            throw new McpToolException(UNAUTHENTICATED);
        }
        String outcome = "success";
        try {
            Result<List<OrgBriefDTO>> result = orgClient.hospitals(principal.hospitalIds());
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                log.warn("MCP 查询医院失败: consumer={}, code={}, msg={}", principal.consumer(),
                        result == null ? null : result.getCode(), result == null ? null : result.getMsg());
                throw new McpToolException(HOSPITAL_ERROR);
            }
            Map<Long, String> returned = new LinkedHashMap<>();
            for (OrgBriefDTO hospital : result.getData()) {
                if (hospital != null && hospital.getId() != null) {
                    returned.put(hospital.getId(), hospital.getName());
                }
            }
            List<HospitalItem> items = principal.hospitalIds().stream()
                    .filter(returned::containsKey)
                    .map(id -> new HospitalItem(id, returned.get(id)))
                    .toList();
            List<Long> missing = principal.hospitalIds().stream()
                    .filter(id -> !returned.containsKey(id))
                    .toList();
            if (!missing.isEmpty()) {
                log.warn("MCP 医院列表缺失: consumer={}, missingHospitalIds={}", principal.consumer(), missing);
            }
            log.info("MCP 医院列表查询成功: consumer={}, count={}", principal.consumer(), items.size());
            return items;
        } catch (McpToolException e) {
            outcome = "error";
            throw e;
        } catch (Exception e) {
            outcome = "error";
            log.warn("MCP 查询医院失败: consumer={}, cause={}", principal.consumer(), e.getMessage());
            throw new McpToolException(HOSPITAL_ERROR);
        } finally {
            recordMetrics(TOOL_HOSPITALS, outcome, start);
            logAccess(TOOL_HOSPITALS, principal, principal.hospitalIds(), start, outcome);
        }
    }

    /**
     * 查询指定医院未来一段时间的医生排班与余量
     *
     * @param hospital_id 医院 ID，单院 Key 可空
     * @param date_from 开始日期（yyyy-MM-dd），默认今天
     * @param date_to 结束日期（yyyy-MM-dd），默认开始日期后 6 天
     * @param department_id 科室 ID（可空）
     * @param doctor_name 医生姓名关键字（可空）
     * @param period 时段（1=上午，2=下午，可空）
     * @param toolContext Spring AI 工具上下文
     * @return 查询结果
     */
    @Tool(name = TOOL_QUERY, description = "查询指定医院医生排班与号源余量，查询窗口最多 14 天。参数：hospital_id=医院ID（单院 Key 可空），date_from=开始日期 yyyy-MM-dd，date_to=结束日期 yyyy-MM-dd（窗口最多14天），department_id=科室ID（可空），doctor_name=医生姓名（可空，包含匹配），period=1上午/2下午（可空）。")
    public Object queryDoctorSchedules(
            @ToolParam(description = "医院ID；Key 只绑定一家医院时可空", required = false) Long hospital_id,
            @ToolParam(description = "开始日期 yyyy-MM-dd，默认今天", required = false) String date_from,
            @ToolParam(description = "结束日期 yyyy-MM-dd，默认开始日期后 6 天，窗口最多 14 天", required = false) String date_to,
            @ToolParam(description = "科室ID，可空", required = false) Long department_id,
            @ToolParam(description = "医生姓名，包含匹配，可空", required = false) String doctor_name,
            @ToolParam(description = "时段 1=上午 2=下午，可空", required = false) Integer period,
            ToolContext toolContext) {
        long start = System.nanoTime();
        McpPrincipal principal = McpPrincipalContext.from(toolContext);
        if (principal == null) {
            log.warn("MCP 工具调用拒绝: tool={}, reason=unauthenticated", TOOL_QUERY);
            recordMetrics(TOOL_QUERY, "error", start);
            throw new McpToolException(UNAUTHENTICATED);
        }
        Long targetHospital = hospital_id;
        String outcome = "success";
        try {
            if (targetHospital == null) {
                if (principal.hospitalIds().size() != 1) {
                    throw new McpToolException("请指定 hospital_id");
                }
                targetHospital = principal.hospitalIds().get(0);
            }
            if (!principal.canAccess(targetHospital)) {
                throw new McpToolException("无权查询该医院");
            }
            if (period != null && period != 1 && period != 2) {
                throw new McpToolException("时段仅支持 1=上午、2=下午");
            }

            LocalDate from;
            LocalDate to;
            try {
                LocalDate today = LocalDate.now(ZONE);
                from = (date_from == null || date_from.isBlank()) ? today : LocalDate.parse(date_from.trim());
                if (from.isBefore(today)) {
                    from = today;
                }
                to = (date_to == null || date_to.isBlank())
                        ? from.plusDays(DEFAULT_WINDOW_DAYS - 1)
                        : LocalDate.parse(date_to.trim());
            } catch (DateTimeParseException e) {
                throw new McpToolException("日期格式应为 yyyy-MM-dd");
            }
            if (from.isAfter(to)) {
                throw new McpToolException("开始日期不能晚于结束日期");
            }
            if (to.isAfter(from.plusDays(MAX_WINDOW_DAYS - 1))) {
                throw new McpToolException("查询窗口最多 " + MAX_WINDOW_DAYS + " 天");
            }
            String keyword = doctor_name == null || doctor_name.isBlank() ? null : doctor_name.trim();
            String cacheKey = cacheKey(targetHospital, from, to, department_id, period, keyword);

            Optional<ScheduleQueryResultDTO> cached = scheduleCache.get(cacheKey);
            ScheduleQueryResultDTO data;
            if (cached.isPresent()) {
                data = cached.get();
            } else {
                Result<ScheduleQueryResultDTO> result = registrationClient.querySchedules(
                        targetHospital, from.toString(), to.toString(), department_id, period, keyword);
                if (result == null || result.getCode() != 200) {
                    log.warn("MCP 排班查询下游失败: consumer={}, hospitalId={}, code={}, msg={}",
                            principal.consumer(), targetHospital,
                            result == null ? null : result.getCode(),
                            result == null ? null : result.getMsg());
                    throw new McpToolException(DOWNSTREAM_ERROR);
                }
                if (result.getData() == null) {
                    log.warn("MCP 排班查询下游无数据: consumer={}, hospitalId={}",
                            principal.consumer(), targetHospital);
                    throw new McpToolException(DOWNSTREAM_ERROR);
                }
                data = result.getData();
                scheduleCache.put(cacheKey, data);
            }

            List<DoctorScheduleInfoDTO> items = data.getItems() == null ? List.of() : data.getItems();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", items);
            payload.put("truncated", data.isTruncated());
            payload.put("dateFrom", from.toString());
            payload.put("dateTo", to.toString());
            log.info("MCP 排班查询成功: consumer={} hospitalId={} from={} to={} rows={} truncated={}",
                    principal.consumer(), targetHospital, from, to,
                    items.size(), data.isTruncated());
            return payload;
        } catch (McpToolException e) {
            outcome = "error";
            throw e;
        } catch (Exception e) {
            outcome = "error";
            log.warn("MCP 排班查询失败: consumer={}, cause={}", principal.consumer(), e.getMessage());
            throw new McpToolException(DOWNSTREAM_ERROR);
        } finally {
            recordMetrics(TOOL_QUERY, outcome, start);
            logAccess(TOOL_QUERY, principal, targetHospital, start, outcome);
        }
    }

    /**
     * 记录工具调用指标，指标故障不影响工具结果
     *
     * @param tool 工具名
     * @param outcome 结果
     * @param start 起始纳秒时间戳
     */
    private void recordMetrics(String tool, String outcome, long start) {
        try {
            metrics.toolCall(tool, outcome, System.nanoTime() - start);
        } catch (Exception e) {
            log.debug("MCP 指标记录失败 tool={}: {}", tool, e.getMessage());
        }
    }

    /**
     * 记录工具访问日志，不含 Key 与患者信息
     *
     * @param tool 工具名
     * @param principal 调用方
     * @param scope 医院范围或医院 ID
     * @param start 起始纳秒时间戳
     * @param outcome 结果
     */
    private void logAccess(String tool, McpPrincipal principal, Object scope, long start, String outcome) {
        log.info("MCP 工具访问 tool={} consumer={} scope={} outcome={} durationMs={}",
                tool, principal.consumer(), scope, outcome,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
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
     * 医院列表项
     *
     * @param hospitalId 医院 ID
     * @param hospitalName 医院名称
     */
    public record HospitalItem(Long hospitalId, String hospitalName) {
    }
}
