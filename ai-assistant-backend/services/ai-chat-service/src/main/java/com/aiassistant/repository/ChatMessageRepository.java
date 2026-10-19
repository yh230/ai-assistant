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

    /**
     * 查询某用户某身份下最近 N 条指定角色的消息（跨会话，画像专用）
     *
     * @param role     消息角色（user/assistant）
     * @param userId   归属用户 ID
     * @param identity 归属身份（staff/patient）
     * @param pageable 分页参数（用于限制条数）
     * @return 归属该用户的最近消息列表；无数据返回空列表
     */
    @Query("""
           SELECT m FROM ChatMessage m
           WHERE m.role = :role
             AND m.sessionId IN (
                 SELECT s.sessionId FROM ChatSession s
                 WHERE s.userId = :userId AND s.identity = :identity)
           ORDER BY m.createdAt DESC
           """)
    List<ChatMessage> findRecentByRoleAndOwner(@Param("role") String role,
                                               @Param("userId") Long userId,
                                               @Param("identity") String identity,
                                               Pageable pageable);

    /**
     * 统计某用户某身份下指定角色的消息数
     *
     * @param role     消息角色（user/assistant）
     * @param userId   归属用户 ID
     * @param identity 归属身份（staff/patient）
     * @return 消息条数
     */
    @Query("""
           SELECT COUNT(m) FROM ChatMessage m
           WHERE m.role = :role
             AND m.sessionId IN (
                 SELECT s.sessionId FROM ChatSession s
                 WHERE s.userId = :userId AND s.identity = :identity)
           """)
    long countByRoleAndOwner(@Param("role") String role,
                             @Param("userId") Long userId,
                             @Param("identity") String identity);
}
