package com.aiassistant.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 会话列表项 DTO
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionDTO {

    private String sessionId;
    private String title;

    /** 会话类型：CHAT=闲聊 / TRIAGE=导诊 / RAG=知识库 */
    private String sessionType;

    private LocalDateTime updatedAt;
}
