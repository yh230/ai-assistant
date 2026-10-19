package com.aiassistant.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 管理端接口权限注解校验（防止后续改动丢失鉴权）
 *
 * @author yh
 * @date 2026-10-19
 */
class AdminEndpointAuthorizationTest {

    @Test
    void adminChatRequiresPermission() throws Exception {
        Method method = AdminChatController.class.getMethod("sessions", String.class, long.class, long.class);
        assertEquals("hasAuthority('ai:chat:admin')",
                method.getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void knowledgeUploadRequiresPermission() throws Exception {
        Method method = KnowledgeController.class.getMethod("upload", org.springframework.web.multipart.MultipartFile.class);
        assertEquals("hasAuthority('ai:knowledge:manage')",
                method.getAnnotation(PreAuthorize.class).value());
    }
}
