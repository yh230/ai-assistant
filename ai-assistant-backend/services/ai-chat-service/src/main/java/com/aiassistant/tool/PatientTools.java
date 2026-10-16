package com.aiassistant.tool;

import com.aiassistant.service.PendingActionService;
import com.api.client.OrgClient;
import com.api.client.RegistrationClient;
import com.api.client.UserClient;
import com.api.dto.OrgBriefDTO;
import com.api.dto.RegistrationBriefDTO;
import com.api.dto.SourceInfoDTO;
import com.api.dto.UserBriefDTO;
import com.common.constant.SecurityConstants;
import com.common.result.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者侧 AI 工具集（ToolCalling）：导诊/挂号工具，数据经内部接口取自真实业务服务
 * <p>
 * 只读工具（科室/号源/我的预约）直接查询业务服务；写工具（预约/退号）不直接执行，
 * 仅落待确认操作（pending_action），患者二次确认后经 ActionExecutor 执行。
 * 工具抛出的 RuntimeException 文案会被 Spring AI 转成工具错误响应，交由模型用自然语言转述；
 * 患者身份与医院范围经 ToolContext 注入（patientId/hospitalId/sessionId），不从模型参数获取。
 *
 * @author yh
 * @date 2026-10-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientTools {

    /** 工具名称：预约挂号 */
    private static final String TOOL_BOOK_APPOINTMENT = "bookAppointment";

    /** 工具名称：取消预约退号 */
    private static final String TOOL_CANCEL_APPOINTMENT = "cancelAppointment";

    private final OrgClient orgClient;
    private final RegistrationClient registrationClient;
    private final UserClient userClient;
    private final PendingActionService pendingActionService;
    private final ObjectMapper objectMapper;

    /**
     * 查询当前患者所属医院的科室列表
     *
     * @param context 工具上下文，hospitalId 由调用方经 ToolContext 注入
     * @return 科室简要信息列表
     * @throws RuntimeException 所属医院未知或科室查询服务不可用时抛出友好文案
     */
    @Tool(description = "查询当前患者所属医院的科室列表。当患者需要挂号、询问看什么科室时需要先调用此工具获取真实科室数据")
    public List<OrgBriefDTO> queryDepartments(ToolContext context) {
        Long hospitalId = (Long) context.getContext().get("hospitalId");
        if (hospitalId == null) {
            log.warn("AI 工具调用：查询科室缺少所属医院");
            throw new RuntimeException("暂时无法确定所属医院，请稍后重试");
        }
        log.info("AI 工具调用：查询科室列表 hospitalId={}", hospitalId);
        Result<List<OrgBriefDTO>> result;
        try {
            result = orgClient.departmentList(hospitalId);
        } catch (Exception e) {
            log.warn("科室查询服务调用失败: hospitalId={}, {}", hospitalId, e.getMessage());
            throw new RuntimeException("科室查询服务暂时不可用，请稍后重试");
        }
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("科室查询服务暂时不可用，请稍后重试");
        }
        return result.getData();
    }

    /**
     * 查询某科室指定日期的号源余量（仅返回余量大于 0 的号源，按患者所属医院约束）
     *
     * @param departmentId 科室ID（取自科室列表）
     * @param date 出诊日期（yyyy-MM-dd）
     * @param period 时段（1=上午 2=下午，可空表示全天）
     * @param context 工具上下文，hospitalId 由调用方经 ToolContext 注入
     * @return 有余量的号源列表
     * @throws RuntimeException 所属医院未知或号源查询服务不可用时抛出友好文案
     */
    @Tool(description = "查询某科室指定日期的号源余量。参数：departmentId=科室ID（取自科室列表），date=日期（yyyy-MM-dd），period=时段（1=上午 2=下午，可空表示全天）")
    public List<SourceInfoDTO> queryNumberSources(Long departmentId, String date, Integer period, ToolContext context) {
        Long hospitalId = (Long) context.getContext().get("hospitalId");
        if (hospitalId == null) {
            log.warn("AI 工具调用：查询号源缺少所属医院");
            throw new RuntimeException("暂时无法确定所属医院，请稍后重试");
        }
        log.info("AI 工具调用：查询号源 departmentId={} date={} period={} hospitalId={}",
                departmentId, date, period, hospitalId);
        Result<List<SourceInfoDTO>> result;
        try {
            result = registrationClient.sources(departmentId, date, period, hospitalId);
        } catch (Exception e) {
            log.warn("号源查询服务调用失败: departmentId={}, {}", departmentId, e.getMessage());
            throw new RuntimeException("号源查询服务暂时不可用，请稍后重试");
        }
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("号源查询服务暂时不可用，请稍后重试");
        }
        return result.getData().stream()
                .filter(s -> s.getRemainingCount() != null && s.getRemainingCount() > 0)
                .toList();
    }

    /**
     * 为当前患者发起预约挂号（写操作，仅生成待确认操作，不直接执行）
     *
     * @param departmentId 科室ID（取自科室列表）
     * @param doctorUserId 医生用户ID（取自号源查询结果）
     * @param date 就诊日期（yyyy-MM-dd）
     * @param period 时段（1=上午 2=下午）
     * @param seqNo 就诊序号（可空，空则由挂号中心自动分配）
     * @param context 工具上下文，sessionId/patientId 由调用方经 ToolContext 注入
     * @return 提示模型已生成确认请求的文案
     */
    @Tool(description = """
            为当前患者发起预约挂号（写操作，系统会要求患者二次确认后才会真正执行）。
            参数：departmentId=科室ID，doctorUserId=医生用户ID（取自号源查询结果），\
            date=就诊日期（yyyy-MM-dd），period=时段（1=上午 2=下午），seqNo=就诊序号（可空）""")
    public String bookAppointment(Long departmentId, Long doctorUserId, String date, Integer period,
                                  Integer seqNo, ToolContext context) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("departmentId", departmentId);
        args.put("doctorUserId", doctorUserId);
        args.put("scheduleDate", date);
        args.put("period", period);
        if (seqNo != null) {
            args.put("seqNo", seqNo);
        }
        String summary = "预约挂号：" + resolveDepartmentName(departmentId, context)
                + " " + resolveDoctorName(doctorUserId) + " " + date + " "
                + (Integer.valueOf(1).equals(period) ? "上午" : "下午");
        pendingActionService.create((String) context.getContext().get("sessionId"),
                (Long) context.getContext().get("patientId"), SecurityConstants.IDENTITY_PATIENT,
                TOOL_BOOK_APPOINTMENT, toJson(args), summary);
        log.info("AI 工具调用：生成预约确认请求 departmentId={} doctorUserId={} date={} period={}",
                departmentId, doctorUserId, date, period);
        return "预约确认请求已生成，请提示患者在弹出的确认窗口确认后完成预约。";
    }

    /**
     * 为当前患者取消预约退号（写操作，仅生成待确认操作，不直接执行）
     *
     * @param registrationId 预约记录ID（取自我的预约列表）
     * @param context 工具上下文，sessionId/patientId 由调用方经 ToolContext 注入
     * @return 提示模型已生成退号确认请求的文案
     */
    @Tool(description = "为当前患者取消预约退号（写操作，系统会要求患者二次确认后才会真正执行）。参数：registrationId=预约记录ID（取自我的预约列表）")
    public String cancelAppointment(Long registrationId, ToolContext context) {
        Map<String, Object> args = Map.of("registrationId", registrationId);
        String summary = "取消预约退号（预约记录ID：" + registrationId + "）";
        pendingActionService.create((String) context.getContext().get("sessionId"),
                (Long) context.getContext().get("patientId"), SecurityConstants.IDENTITY_PATIENT,
                TOOL_CANCEL_APPOINTMENT, toJson(args), summary);
        log.info("AI 工具调用：生成退号确认请求 registrationId={}", registrationId);
        return "退号确认请求已生成，请提示患者在弹出的确认窗口确认后完成退号。";
    }

    /**
     * 查询当前患者的全部预约记录（含状态、就诊日期、科室、医生）
     *
     * @param context 工具上下文，patientId 由调用方经 ToolContext 注入
     * @return 预约记录简要信息列表
     * @throws RuntimeException 预约查询服务不可用时抛出友好文案
     */
    @Tool(description = "查询当前患者的全部预约记录（含状态、就诊日期、科室、医生）")
    public List<RegistrationBriefDTO> myAppointments(ToolContext context) {
        Long patientId = (Long) context.getContext().get("patientId");
        log.info("AI 工具调用：查询我的预约 patientId={}", patientId);
        Result<List<RegistrationBriefDTO>> result;
        try {
            result = registrationClient.patientRegistrations(patientId);
        } catch (Exception e) {
            log.warn("预约查询服务调用失败: patientId={}, {}", patientId, e.getMessage());
            throw new RuntimeException("预约查询服务暂时不可用，请稍后重试");
        }
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("预约查询服务暂时不可用，请稍后重试");
        }
        return result.getData();
    }

    /**
     * 解析科室名称（解析失败回退“科室+ID”，不中断写工具主流程）
     *
     * @param departmentId 科室ID
     * @param context 工具上下文，hospitalId 由调用方经 ToolContext 注入
     * @return 科室名称
     */
    private String resolveDepartmentName(Long departmentId, ToolContext context) {
        try {
            Long hospitalId = (Long) context.getContext().get("hospitalId");
            if (hospitalId == null) {
                return "科室" + departmentId;
            }
            Result<List<OrgBriefDTO>> result = orgClient.departmentList(hospitalId);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                return result.getData().stream()
                        .filter(d -> departmentId != null && departmentId.equals(d.getId()))
                        .map(OrgBriefDTO::getName)
                        .findFirst()
                        .orElse("科室" + departmentId);
            }
        } catch (Exception e) {
            log.warn("科室名称解析失败: departmentId={}, {}", departmentId, e.getMessage());
        }
        return "科室" + departmentId;
    }

    /**
     * 解析医生姓名（解析失败回退“医生+ID”，不中断写工具主流程）
     *
     * @param doctorUserId 医生用户ID
     * @return 医生姓名
     */
    private String resolveDoctorName(Long doctorUserId) {
        try {
            Result<List<UserBriefDTO>> result = userClient.batch(List.of(doctorUserId));
            if (result != null && result.getCode() == 200 && result.getData() != null
                    && !result.getData().isEmpty()) {
                String name = result.getData().get(0).getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception e) {
            log.warn("医生姓名解析失败: doctorUserId={}, {}", doctorUserId, e.getMessage());
        }
        return "医生" + doctorUserId;
    }

    /**
     * 将工具参数序列化为 JSON 字符串
     *
     * @param args 工具参数
     * @return JSON 字符串
     * @throws RuntimeException 参数序列化失败时抛出友好文案
     */
    private String toJson(Object args) {
        try {
            return objectMapper.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            log.warn("工具参数序列化失败: {}", e.getMessage());
            throw new RuntimeException("操作参数处理失败，请稍后重试");
        }
    }
}
