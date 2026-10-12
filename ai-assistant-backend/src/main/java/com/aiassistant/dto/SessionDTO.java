package com.aiassistant.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 会话列表项 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionDTO {

    private String sessionId;
    private String title;
    private LocalDateTime updatedAt;
}
