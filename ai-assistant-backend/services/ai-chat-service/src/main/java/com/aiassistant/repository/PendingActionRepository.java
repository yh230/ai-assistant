package com.aiassistant.repository;

import com.aiassistant.entity.PendingAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 待确认操作 Repository（二次确认状态机持久化）
 *
 * @author yh
 * @date 2026-10-13
 */
@Repository
public interface PendingActionRepository extends JpaRepository<PendingAction, Long> {

    /**
     * 按确认ID查询
     *
     * @param actionId 确认ID（前端回传的业务标识）
     * @return 命中的待确认操作；未命中返回空
     */
    Optional<PendingAction> findByActionId(String actionId);

    /**
     * 查询会话下指定状态且未过期的最新一条记录（expire_time 晚于当前时间，按创建时间倒序取首条）
     *
     * @param sessionId 会话标识
     * @param status    目标状态（如 PENDING）
     * @param now       当前时间（过期边界：仅返回 expire_time 晚于该值的记录）
     * @return 最新的未过期待确认操作；无数据返回空
     */
    Optional<PendingAction> findFirstBySessionIdAndStatusAndExpireTimeAfterOrderByCreateTimeDesc(
            String sessionId, String status, LocalDateTime now);

    /**
     * 条件更新状态：仅当记录仍为 PENDING 时流转（并发重复确认/取消的防重放闸门）
     *
     * @param actionId    确认ID
     * @param status      目标状态（CONFIRMED/CANCELLED/EXPIRED）
     * @param confirmTime 确认时间（确认时填充；取消/过期传 null）
     * @return 受影响行数；0 表示已被其他请求先行流转
     */
    @Modifying
    @Query("UPDATE PendingAction p SET p.status = :status, p.confirmTime = :confirmTime "
            + "WHERE p.actionId = :actionId AND p.status = 'PENDING'")
    int markStatus(@Param("actionId") String actionId, @Param("status") String status,
                   @Param("confirmTime") LocalDateTime confirmTime);
}
