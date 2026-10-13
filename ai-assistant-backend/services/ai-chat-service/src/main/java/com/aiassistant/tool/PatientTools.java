package com.aiassistant.tool;

import com.api.client.OrgClient;
import com.api.client.RegistrationClient;
import com.api.dto.OrgBriefDTO;
import com.api.dto.SourceInfoDTO;
import com.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 患者侧 AI 工具集（ToolCalling）：导诊/挂号只读工具，数据经内部接口取自真实业务服务
 * <p>
 * 工具抛出的 RuntimeException 文案会被 Spring AI 转成工具错误响应，交由模型用自然语言转述；
 * 患者身份与医院范围经 ToolContext 注入（patientId/hospitalId），不从模型参数获取。
 *
 * @author yh
 * @date 2026-10-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientTools {

    private final OrgClient orgClient;
    private final RegistrationClient registrationClient;

    /**
     * 查询当前患者所属医院的科室列表
     *
     * @param context 工具上下文，hospitalId 由调用方经 ToolContext 注入
     * @return 科室简要信息列表
     * @throws RuntimeException 科室查询服务不可用时抛出友好文案
     */
    @Tool(description = "查询当前患者所属医院的科室列表。当患者需要挂号、询问看什么科室时需要先调用此工具获取真实科室数据")
    public List<OrgBriefDTO> queryDepartments(ToolContext context) {
        Long hospitalId = (Long) context.getContext().get("hospitalId");
        log.info("AI 工具调用：查询科室列表 hospitalId={}", hospitalId);
        Result<List<OrgBriefDTO>> result = orgClient.departmentList(hospitalId);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("科室查询服务暂时不可用，请稍后重试");
        }
        return result.getData();
    }

    /**
     * 查询某科室指定日期的号源余量（仅返回余量大于 0 的号源）
     *
     * @param departmentId 科室ID（取自科室列表）
     * @param date 出诊日期（yyyy-MM-dd）
     * @param period 时段（1=上午 2=下午，可空表示全天）
     * @param context 工具上下文，hospitalId 由调用方经 ToolContext 注入
     * @return 有余量的号源列表
     * @throws RuntimeException 号源查询服务不可用时抛出友好文案
     */
    @Tool(description = "查询某科室指定日期的号源余量。参数：departmentId=科室ID（取自科室列表），date=日期（yyyy-MM-dd），period=时段（1=上午 2=下午，可空表示全天）")
    public List<SourceInfoDTO> queryNumberSources(Long departmentId, String date, Integer period, ToolContext context) {
        log.info("AI 工具调用：查询号源 departmentId={} date={} period={} hospitalId={}",
                departmentId, date, period, context.getContext().get("hospitalId"));
        Result<List<SourceInfoDTO>> result = registrationClient.sources(departmentId, date, period);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new RuntimeException("号源查询服务暂时不可用，请稍后重试");
        }
        return result.getData().stream()
                .filter(s -> s.getRemainingCount() != null && s.getRemainingCount() > 0)
                .toList();
    }
}
