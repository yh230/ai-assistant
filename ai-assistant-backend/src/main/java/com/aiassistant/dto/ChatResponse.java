package com.aiassistant.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天响应 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponse {

    /** 会话ID */
    private String sessionId;

    /** AI 回复内容 */
    private String reply;

    /** 回复时间 */
    private LocalDateTime timestamp;

    /** 是否使用了本地回复引擎 */
    private boolean localFallback;

    /** 完整的对话历史（可选） */
    private List<ChatMessageDTO> messages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChatMessageDTO {
        private String role;
        private String content;
        private LocalDateTime createdAt;
    }
}
