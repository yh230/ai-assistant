package com.aiassistant.service;

import com.aiassistant.entity.ChatMessage;
import com.aiassistant.repository.ChatMessageRepository;
import com.aiassistant.repository.ChatSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户画像构建单测（验证按登录人隔离）
 *
 * @author yh
 * @date 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryBuilderTest {

    @Mock private ChatMessageRepository messageRepository;
    @Mock private ChatSessionRepository sessionRepository;

    @InjectMocks private UserMemoryBuilder builder;

    @Test
    void buildsMemoryFromCurrentUserOnly() {
        when(sessionRepository.countByUserIdAndIdentity(7L, "staff")).thenReturn(3L);
        when(messageRepository.countByRoleAndOwner("user", 7L, "staff")).thenReturn(12L);
        ChatMessage msg = new ChatMessage();
        msg.setRole("user");
        msg.setContent("我最近在北京工作，喜欢看电影");
        when(messageRepository.findRecentByRoleAndOwner(eq("user"), eq(7L), eq("staff"), any()))
                .thenReturn(List.of(msg));

        String memory = builder.build(7L, "staff");

        assertTrue(memory.contains("3 次"));
        assertTrue(memory.contains("北京"));
        assertTrue(memory.contains("电影"));
        verify(messageRepository, never()).countByRole(anyString());
        verify(sessionRepository, never()).count();
    }

    @Test
    void returnsEmptyWhenNoSessions() {
        when(sessionRepository.countByUserIdAndIdentity(7L, "staff")).thenReturn(0L);
        assertEquals("", builder.build(7L, "staff"));
    }
}
