package com.aiassistant.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 聊天会话实体（带归属用户与身份，供按登录人隔离会话）
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
@Entity
@Table(name = "chat_session")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 200)
    private String title;

    /** 归属用户 ID（员工=sys_user.id，患者=patient_user.id） */
    @Column(name = "user_id")
    private Long userId;

    /** 归属身份（staff/patient，见 SecurityConstants 语义） */
    @Column(length = 10)
    private String identity;

    /** 会话类型：CHAT=闲聊 / TRIAGE=导诊 / RAG=知识库 */
    @Column(name = "session_type", length = 10)
    private String sessionType;

    /** 归属医院 ID（快照冗余，供管理员数据权限过滤） */
    @Column(name = "hospital_id")
    private Long hospitalId;

    /** 会话绑定的人设 ID（创建时解析写入；存量会话可能为空） */
    @Column(name = "role_id", length = 32)
    private String roleId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
