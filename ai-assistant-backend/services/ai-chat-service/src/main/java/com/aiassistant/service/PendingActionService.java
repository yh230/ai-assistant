package com.aiassistant.service;

import com.aiassistant.entity.PendingAction;
import com.common.exception.BusinessException;

import java.util.Optional;

/**
 * 待确认操作服务（写工具二次确认状态机）
 *
 * @author yh
 * @date 2026-10-13
 */
public interface PendingActionService {

    /** 状态常量：待确认 */
    String STATUS_PENDING = "PENDING";

    /** 状态常量：已确认 */
    String STATUS_CONFIRMED = "CONFIRMED";

    /** 状态常量：已取消 */
    String STATUS_CANCELLED = "CANCELLED";

    /** 状态常量：已过期 */
    String STATUS_EXPIRED = "EXPIRED";

    /** 待确认操作有效期（分钟） */
    int EXPIRY_MINUTES = 5;

    /**
     * 创建待确认操作（生成确认ID 并设置过期时间）
     *
     * @param sessionId    归属会话标识
     * @param userId       归属用户 ID
     * @param identity     归属身份（patient）
     * @param toolName     工具名称（bookAppointment/cancelAppointment）
     * @param toolArgsJson 工具参数 JSON
     * @param summaryText  确认弹窗展示摘要
     * @return 已落库的待确认操作（状态 PENDING）
     */
    PendingAction create(String sessionId, Long userId, String identity,
                         String toolName, String toolArgsJson, String summaryText);

    /**
     * 确认操作（校验归属/有效期/状态，通过后置 CONFIRMED）
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @return 已确认的操作（状态 CONFIRMED，含确认时间）
     * @throws BusinessException 请求不存在/无权操作/已超时/已处理
     */
    PendingAction confirm(String actionId, Long userId, String identity);

    /**
     * 取消操作（校验归属/有效期/状态，通过后置 CANCELLED）
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @throws BusinessException 请求不存在/无权操作/已超时/已处理
     */
    void cancel(String actionId, Long userId, String identity);

    /**
     * 查询会话下最新的未过期待确认操作（状态 PENDING 且 expireTime 未过期）
     *
     * @param sessionId 会话标识
     * @return 最新的未过期 PENDING 操作；无数据返回空
     */
    Optional<PendingAction> findLatestPending(String sessionId);
}
