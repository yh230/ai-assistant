package com.aiassistant.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 */
@Data
@Entity
@Table(name = "chat_message")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 20)
    private String role; // user / assistant

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 引用来源 JSON（RAG 回答的知识库来源：文件名+摘要；无来源为 null） */
    @Column(name = "reference_json", columnDefinition = "TEXT")
    private String referenceJson;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
