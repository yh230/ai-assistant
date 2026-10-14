package com.aiassistant.service.impl;

import com.aiassistant.entity.PendingAction;
import com.aiassistant.repository.PendingActionRepository;
import com.aiassistant.service.PendingActionService;
import com.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 待确认操作服务实现（写操作防伪确认核心状态机）
 * <p>
 * 状态流转：PENDING → CONFIRMED（确认）/ CANCELLED（取消）/ EXPIRED（过期）。
 * 归属（用户 + 身份）、有效期（{@link PendingActionService#EXPIRY_MINUTES} 分钟）与状态三重校验防重放，
 * 状态置位统一走「仅 PENDING 可流转」的条件更新，并发重复确认/取消时后到者被拒；
 * 过期拒绝时置位的 EXPIRED 需随异常一并落库，故业务异常不触发事务回滚。
 *
 * @author yh
 * @date 2026-10-13
 */
@Service
@RequiredArgsConstructor
public class PendingActionServiceImpl implements PendingActionService {

    private final PendingActionRepository pendingActionRepository;

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
    @Override
    @Transactional
    public PendingAction create(String sessionId, Long userId, String identity,
                                String toolName, String toolArgsJson, String summaryText) {
        PendingAction action = new PendingAction();
        action.setActionId(UUID.randomUUID().toString().replace("-", ""));
        action.setSessionId(sessionId);
        action.setUserId(userId);
        action.setIdentity(identity);
        action.setToolName(toolName);
        action.setToolArgs(toolArgsJson);
        action.setSummaryText(summaryText);
        action.setStatus(STATUS_PENDING);
        action.setExpireTime(LocalDateTime.now().plusMinutes(EXPIRY_MINUTES));
        return pendingActionRepository.save(action);
    }

    /**
     * 确认操作（校验归属/有效期/状态，通过后条件置 CONFIRMED 防并发重放）
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @return 已确认的操作（状态 CONFIRMED，含确认时间）
     * @throws BusinessException 请求不存在/无权操作/已超时/已处理（含并发重复确认）
     */
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public PendingAction confirm(String actionId, Long userId, String identity) {
        PendingAction action = loadOwnedPending(actionId, userId, identity);
        LocalDateTime confirmTime = LocalDateTime.now();
        if (pendingActionRepository.markStatus(actionId, STATUS_CONFIRMED, confirmTime) == 0) {
            // 条件更新未命中：记录已被并发请求流转，按已处理拒绝
            throw new BusinessException("该操作已处理");
        }
        action.setStatus(STATUS_CONFIRMED);
        action.setConfirmTime(confirmTime);
        return action;
    }

    /**
     * 取消操作（校验归属/有效期/状态，通过后条件置 CANCELLED 防并发重放）
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @throws BusinessException 请求不存在/无权操作/已超时/已处理（含并发重复取消）
     */
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public void cancel(String actionId, Long userId, String identity) {
        PendingAction action = loadOwnedPending(actionId, userId, identity);
        // 取消不写确认时间：PENDING 记录 confirm_time 必为空，条件更新置 null 保持原语义
        if (pendingActionRepository.markStatus(actionId, STATUS_CANCELLED, null) == 0) {
            throw new BusinessException("该操作已处理");
        }
        action.setStatus(STATUS_CANCELLED);
    }

    /**
     * 查询会话下最新的未过期待确认操作（状态 PENDING 且 expireTime 未过期）
     *
     * @param sessionId 会话标识
     * @return 最新的未过期 PENDING 操作；无数据返回空
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<PendingAction> findLatestPending(String sessionId) {
        // 过期仅随确认/取消惰性置位，查询侧必须自带时间边界，避免历史过期 PENDING 每轮被重推 confirm_required
        return pendingActionRepository.findFirstBySessionIdAndStatusAndExpireTimeAfterOrderByCreateTimeDesc(
                sessionId, STATUS_PENDING, LocalDateTime.now());
    }

    /**
     * 加载并校验待确认操作（存在性 → 归属 → 状态 → 有效期）
     * <p>
     * 不存在/归属不符直接拒绝；状态非 PENDING（已确认/已取消/已过期）一律按已处理拒绝，不覆写终态；
     * 过期仅对 PENDING 条件置 EXPIRED 落库后按超时拒绝。
     * 状态检查兼作防重放，条件更新兜底并发场景，已流转的记录不可再次流转。
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @return 通过校验的 PENDING 操作
     * @throws BusinessException 请求不存在/无权操作/已处理/已超时
     */
    private PendingAction loadOwnedPending(String actionId, Long userId, String identity) {
        PendingAction action = pendingActionRepository.findByActionId(actionId)
                .orElseThrow(() -> new BusinessException("确认请求不存在或已失效"));
        if (!userId.equals(action.getUserId()) || !identity.equals(action.getIdentity())) {
            throw new BusinessException("无权操作该确认请求");
        }
        if (!STATUS_PENDING.equals(action.getStatus())) {
            // 已 CONFIRMED/CANCELLED/EXPIRED 的记录（含已过期的终态）直接按已处理拒绝，不覆写终态
            throw new BusinessException("该操作已处理");
        }
        if (LocalDateTime.now().isAfter(action.getExpireTime())) {
            // 仅 PENDING 条件置 EXPIRED；未命中说明已被并发流转，按已处理拒绝
            if (pendingActionRepository.markStatus(actionId, STATUS_EXPIRED, null) == 0) {
                throw new BusinessException("该操作已处理");
            }
            action.setStatus(STATUS_EXPIRED);
            throw new BusinessException("确认已超时，请重新操作");
        }
        return action;
    }
}
