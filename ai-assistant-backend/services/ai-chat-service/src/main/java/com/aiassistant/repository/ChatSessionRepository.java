package com.aiassistant.repository;

import com.aiassistant.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 聊天会话 Repository
 *
 * @author yh
 * @date 2026-10-10
 */
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findBySessionId(String sessionId);

    List<ChatSession> findAllByOrderByUpdatedAtDesc();

    /**
     * 按会话标识 + 归属（用户 + 身份）查询
     *
     * @param sessionId 会话标识
     * @param userId    归属用户 ID
     * @param identity  归属身份（staff/patient）
     * @return 归属匹配的会话；未命中返回空
     */
    Optional<ChatSession> findBySessionIdAndUserIdAndIdentity(String sessionId, Long userId, String identity);

    /**
     * 查询归属某用户某身份的会话，按更新时间倒序
     *
     * @param userId   归属用户 ID
     * @param identity 归属身份（staff/patient）
     * @return 会话列表；无数据返回空列表
     */
    List<ChatSession> findAllByUserIdAndIdentityOrderByUpdatedAtDesc(Long userId, String identity);
}
