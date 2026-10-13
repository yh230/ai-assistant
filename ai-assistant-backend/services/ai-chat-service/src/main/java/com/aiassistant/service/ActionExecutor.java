package com.aiassistant.service;

import com.aiassistant.entity.PendingAction;
import com.api.client.RegistrationClient;
import com.api.dto.BookRequest;
import com.api.dto.CancelRequest;
import com.common.auth.UserSnapshot;
import com.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 已确认写工具的执行分发（fail-soft：业务错误原文透传，服务异常返回固定文案）
 * <p>
 * 仅服务于二次确认后的执行链路：待确认操作经 {@code PendingActionService.confirm} 置为 CONFIRMED 后，
 * 由本类按工具名称分发到挂号中心内部写接口；结果文本交由模型转述给患者。
 * 患者身份字段一律取自登录快照（patientId/姓名/手机号），不从工具参数获取。
 *
 * @author yh
 * @date 2026-10-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionExecutor {

    /** 工具名称：预约挂号 */
    private static final String TOOL_BOOK_APPOINTMENT = "bookAppointment";

    /** 工具名称：取消预约退号 */
    private static final String TOOL_CANCEL_APPOINTMENT = "cancelAppointment";

    private final RegistrationClient registrationClient;
    private final ObjectMapper objectMapper;

    /**
     * 执行已确认的写工具
     *
     * @param action 待确认操作（状态已 CONFIRMED，含工具名称与参数 JSON）
     * @param user   当前患者快照（patientId/姓名/手机号来源）
     * @return 供模型转述的结果文本
     */
    public String execute(PendingAction action, UserSnapshot user) {
        try {
            if (TOOL_BOOK_APPOINTMENT.equals(action.getToolName())) {
                BookRequest req = objectMapper.readValue(action.getToolArgs(), BookRequest.class);
                req.setPatientId(user.getUserId());
                req.setPatientName(user.getName());
                req.setPatientPhone(user.getUsername());
                Result<Long> result = registrationClient.book(req);
                if (result == null) {
                    return "挂号服务暂时不可用，请稍后重试";
                }
                if (result.getCode() != 200) {
                    return result.getMsg();
                }
                return "预约成功，挂号记录ID：" + result.getData();
            }
            if (TOOL_CANCEL_APPOINTMENT.equals(action.getToolName())) {
                CancelRequest req = objectMapper.readValue(action.getToolArgs(), CancelRequest.class);
                req.setPatientId(user.getUserId());
                Result<Void> result = registrationClient.cancel(req);
                if (result == null) {
                    return "挂号服务暂时不可用，请稍后重试";
                }
                if (result.getCode() != 200) {
                    return result.getMsg();
                }
                return "退号成功，号源已释放";
            }
            return "不支持的操作类型";
        } catch (Exception e) {
            log.warn("写工具执行失败: tool={}, {}", action.getToolName(), e.getMessage());
            return "挂号服务暂时不可用，请稍后重试";
        }
    }
}
