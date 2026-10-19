package com.aiassistant.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 助手角色实体。
 * <p>
 * 角色信息由数据库驱动，可在「角色管理」页面自由增删改查，
 * 启动时若角色表为空，由内置默认角色首次播种到本表，之后完全以库内数据为准。
 * 主键 {@code id} 即角色标识，与前端请求体中的 roleId 一致。
 *
 * @author AI Assistant
 */
@Data
@Entity
@Table(name = "ai_role")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    /** 角色ID（即角色标识，如 chat_buddy、tutor），作为主键 */
    @Id
    @Column(name = "id", nullable = false, length = 32)
    private String id;

    /** 角色显示名称，如「聊天伙伴」 */
    @Column(nullable = false, length = 50)
    private String name;

    /** 角色图标（emoji），用于前端展示 */
    @Column(length = 16)
    private String avatar;

    /** 角色简介，用于选择卡片与列表展示 */
    @Column(length = 255)
    private String description;

    /** 系统提示词：定义角色人设与行为规范（支持多行） */
    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    /** 展示排序，数值越小越靠前 */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** 是否启用：禁用后聊天选择器不再展示，且不可用于对话 */
    @Column(nullable = false, columnDefinition = "tinyint(1) default 1")
    private Boolean enabled;

    /** 角色域：STAFF-员工，PATIENT-患者 */
    @Column(nullable = false, length = 10, columnDefinition = "varchar(10) default 'STAFF'")
    private String scope = "STAFF";

    /** 患者会话类型：CHAT/TRIAGE/RAG（员工角色为空） */
    @Column(name = "session_type", length = 10)
    private String sessionType;

    /** 所属医院 ID（患者角色必填；员工角色为空表示全局） */
    @Column(name = "hospital_id")
    private Long hospitalId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.enabled == null) {
            this.enabled = Boolean.TRUE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
