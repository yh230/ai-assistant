package com.aiassistant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待确认操作（写工具二次确认状态机载体）
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
@Entity
@Table(name = "pending_action")
public class PendingAction {

    /** 主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 确认ID（前端回传的业务标识） */
    @Column(name = "action_id", nullable = false, unique = true, length = 64)
    private String actionId;

    /** 归属会话 */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 归属用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 归属身份（patient） */
    @Column(nullable = false, length = 10)
    private String identity;

    /** 工具名称（bookAppointment/cancelAppointment） */
    @Column(name = "tool_name", nullable = false, length = 50)
    private String toolName;

    /** 工具参数（JSON） */
    @Column(name = "tool_args", columnDefinition = "TEXT")
    private String toolArgs;

    /** 确认弹窗展示摘要（如：预约挂号：内科 王医生 2026-10-15 上午） */
    @Column(name = "summary_text", length = 500)
    private String summaryText;

    /** 状态：PENDING/CONFIRMED/CANCELLED/EXPIRED */
    @Column(nullable = false, length = 12)
    private String status;

    /** 创建时间（落库时自动填充） */
    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    /** 过期时间（创建时间 + 有效期） */
    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    /** 确认时间（确认成功时填充） */
    @Column(name = "confirm_time")
    private LocalDateTime confirmTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }
}
