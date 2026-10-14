package com.aiassistant.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 管理端患者会话列表项 DTO
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminSessionDTO {

    /** 会话标识（对外唯一） */
    private String sessionId;

    /** 会话标题 */
    private String title;

    /** 会话类型：CHAT=闲聊 / TRIAGE=导诊 / RAG=知识库 */
    private String sessionType;

    /** 患者用户 ID（patient_user.id） */
    private Long userId;

    /** 患者姓名（患者中心批量回填，不可用时为空） */
    private String patientName;

    /** 患者手机号（患者中心批量回填，不可用时为空） */
    private String patientPhone;

    /** 归属医院 ID（会话快照冗余） */
    private Long hospitalId;

    /** 会话创建时间 */
    private LocalDateTime createdAt;

    /** 会话最后更新时间 */
    private LocalDateTime updatedAt;
}
