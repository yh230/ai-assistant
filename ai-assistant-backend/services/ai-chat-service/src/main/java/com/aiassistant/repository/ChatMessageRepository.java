package com.aiassistant.repository;

import com.aiassistant.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 聊天消息 Repository
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    void deleteBySessionId(String sessionId);

    /**
     * 查询最近 N 条指定角色的消息（跨会话，用于用户画像分析）
     */
    @Query("SELECT m FROM ChatMessage m WHERE m.role = :role ORDER BY m.createdAt DESC")
    List<ChatMessage> findRecentByRole(@Param("role") String role, Pageable pageable);

    /**
     * 便捷方法：取最近 50 条用户消息
     */
    default List<ChatMessage> findRecentUserMessages(int limit) {
        return findRecentByRole("user", Pageable.ofSize(limit));
    }

    /**
     * 统计各角色的消息总数
     */
    long countByRole(String role);
}
