package com.aiassistant.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 管理端接口权限注解校验（防止后续改动丢失鉴权）
 *
 * @author yh
 * @date 2026-10-19
 */
class AdminEndpointAuthorizationTest {

    @Test
    void adminChatRequiresPermission() throws Exception {
        Method sessions = AdminChatController.class.getMethod("sessions", String.class, long.class, long.class);
        PreAuthorize sessionsAuth = sessions.getAnnotation(PreAuthorize.class);
        assertNotNull(sessionsAuth, "sessions 缺少 @PreAuthorize");
        assertEquals("hasAuthority('ai:chat:admin')", sessionsAuth.value());

        Method history = AdminChatController.class.getMethod("history", String.class);
        PreAuthorize historyAuth = history.getAnnotation(PreAuthorize.class);
        assertNotNull(historyAuth, "history 缺少 @PreAuthorize");
        assertEquals("hasAuthority('ai:chat:admin')", historyAuth.value());
    }

    @Test
    void knowledgeUploadRequiresPermission() throws Exception {
        Method upload = KnowledgeController.class.getMethod("upload", MultipartFile.class);
        PreAuthorize uploadAuth = upload.getAnnotation(PreAuthorize.class);
        assertNotNull(uploadAuth, "upload 缺少 @PreAuthorize");
        assertEquals("hasAuthority('ai:knowledge:manage')", uploadAuth.value());
    }

    @Test
    void knowledgePageRequiresPermission() throws Exception {
        Method page = KnowledgeController.class.getMethod("page", long.class, long.class);
        PreAuthorize pageAuth = page.getAnnotation(PreAuthorize.class);
        assertNotNull(pageAuth, "page 缺少 @PreAuthorize");
        assertEquals("hasAuthority('ai:knowledge:manage')", pageAuth.value());
    }

    @Test
    void knowledgeDeleteRequiresPermission() throws Exception {
        Method delete = KnowledgeController.class.getMethod("delete", Long.class);
        PreAuthorize deleteAuth = delete.getAnnotation(PreAuthorize.class);
        assertNotNull(deleteAuth, "delete 缺少 @PreAuthorize");
        assertEquals("hasAuthority('ai:knowledge:manage')", deleteAuth.value());
    }
}
