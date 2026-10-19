package com.aiassistant.service.impl;

import com.aiassistant.entity.ChatSession;
import com.aiassistant.service.AssistantRoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 员工会话人设绑定解析单测
 *
 * @author yh
 * @date 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplEffectiveRoleTest {

    @Mock
    private AssistantRoleService assistantRoleService;

    @InjectMocks
    private ChatServiceImpl chatService;

    @Test
    void usesSessionRoleWhenPresent() {
        ChatSession session = ChatSession.builder().roleId("tutor").build();
        assertEquals("tutor", chatService.resolveEffectiveRoleId(session, "chat_buddy"));
    }

    @Test
    void fallsBackToRequestedWhenSessionRoleMissing() {
        ChatSession session = ChatSession.builder().build();
        when(assistantRoleService.resolveRoleId("tutor")).thenReturn("tutor");
        assertEquals("tutor", chatService.resolveEffectiveRoleId(session, "tutor"));
    }

    @Test
    void fallsBackToRequestedWhenSessionRoleBlank() {
        ChatSession session = ChatSession.builder().roleId("  ").build();
        when(assistantRoleService.resolveRoleId(null)).thenReturn("chat_buddy");
        assertEquals("chat_buddy", chatService.resolveEffectiveRoleId(session, null));
    }
}
