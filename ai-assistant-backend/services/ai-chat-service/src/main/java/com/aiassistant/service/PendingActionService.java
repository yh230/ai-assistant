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

    /** 状态常量：执行中（已锁定，禁止并发重放） */
    String STATUS_EXECUTING = "EXECUTING";

    /** 状态常量：已确认且执行成功 */
    String STATUS_CONFIRMED = "CONFIRMED";

    /** 状态常量：执行失败（业务失败/服务异常，不再自动重试） */
    String STATUS_FAILED = "FAILED";

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
     * 开始执行（校验归属/有效期/状态，通过后条件置 EXECUTING 防并发重放）
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @return 已锁定执行的操作（状态 EXECUTING）
     * @throws BusinessException 请求不存在/无权操作/已超时/已处理
     */
    PendingAction begin(String actionId, Long userId, String identity);

    /**
     * 完成执行：EXECUTING → CONFIRMED（成功，写确认时间）或 FAILED（失败可审计）
     *
     * @param actionId 确认ID
     * @param success  是否执行成功
     */
    void complete(String actionId, boolean success);

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
     * 取消会话下所有待确认操作（删除会话时调用）
     *
     * @param sessionId 会话标识
     * @return 受影响行数
     */
    int cancelBySession(String sessionId);

    /**
     * 批量清理超时未确认操作（定时任务调用）
     *
     * @return 受影响行数
     */
    int expireOverdue();

    /**
     * 查询会话下最新的未过期待确认操作（状态 PENDING 且 expireTime 未过期）
     *
     * @param sessionId 会话标识
     * @return 最新的未过期 PENDING 操作；无数据返回空
     */
    Optional<PendingAction> findLatestPending(String sessionId);
}
