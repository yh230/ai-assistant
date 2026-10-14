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
 * 归属（用户 + 身份）、有效期（{@link PendingActionService#EXPIRY_MINUTES} 分钟）与状态三重校验防重放；
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
     * 确认操作（校验归属/有效期/状态，通过后置 CONFIRMED）
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @return 已确认的操作（状态 CONFIRMED，含确认时间）
     * @throws BusinessException 请求不存在/无权操作/已超时/已处理
     */
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public PendingAction confirm(String actionId, Long userId, String identity) {
        PendingAction action = loadOwnedPending(actionId, userId, identity);
        action.setStatus(STATUS_CONFIRMED);
        action.setConfirmTime(LocalDateTime.now());
        return pendingActionRepository.save(action);
    }

    /**
     * 取消操作（校验归属/有效期/状态，通过后置 CANCELLED）
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @throws BusinessException 请求不存在/无权操作/已超时/已处理
     */
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public void cancel(String actionId, Long userId, String identity) {
        PendingAction action = loadOwnedPending(actionId, userId, identity);
        action.setStatus(STATUS_CANCELLED);
        pendingActionRepository.save(action);
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
     * 加载并校验待确认操作（存在性 → 归属 → 有效期 → 状态）
     * <p>
     * 不存在/归属不符直接拒绝；已过期置 EXPIRED 落库后拒绝；状态非 PENDING 按已处理拒绝。
     * 状态检查兼作防重放，已确认/已取消/已过期的记录不可再次流转。
     *
     * @param actionId 确认ID
     * @param userId   操作者用户 ID
     * @param identity 操作者身份
     * @return 通过校验的 PENDING 操作
     * @throws BusinessException 请求不存在/无权操作/已超时/已处理
     */
    private PendingAction loadOwnedPending(String actionId, Long userId, String identity) {
        PendingAction action = pendingActionRepository.findByActionId(actionId)
                .orElseThrow(() -> new BusinessException("确认请求不存在或已失效"));
        if (!userId.equals(action.getUserId()) || !identity.equals(action.getIdentity())) {
            throw new BusinessException("无权操作该确认请求");
        }
        if (LocalDateTime.now().isAfter(action.getExpireTime())) {
            action.setStatus(STATUS_EXPIRED);
            pendingActionRepository.save(action);
            throw new BusinessException("确认已超时，请重新操作");
        }
        if (!STATUS_PENDING.equals(action.getStatus())) {
            throw new BusinessException("该操作已处理");
        }
        return action;
    }
}
