package com.aiassistant.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 角色实体 scope 默认值单测
 * <p>
 * 覆盖两条易漏路径：Builder 构造需带出默认值，生命周期回调需为非空列兜底。
 *
 * @author yh
 * @date 2026-10-16
 */
class RoleBuilderDefaultTest {

    /**
     * Builder 构造未显式指定 scope 时应默认 STAFF
     */
    @Test
    void builderDefaultsScopeToStaff() {
        Role role = Role.builder().id("chat_buddy").name("聊天伙伴").build();
        assertEquals("STAFF", role.getScope());
    }

    /**
     * 持久化回调应为 null 或空白的 scope 兜底为 STAFF
     */
    @Test
    void prePersistGuardRestoresMissingScope() {
        Role nullScope = Role.builder().id("null_scope").name("空域").scope(null).build();
        nullScope.onCreate();
        assertEquals("STAFF", nullScope.getScope());

        Role blankScope = Role.builder().id("blank_scope").name("空白域").scope("  ").build();
        blankScope.onCreate();
        assertEquals("STAFF", blankScope.getScope());
    }

    /**
     * 显式指定的角色域不应被回调覆盖
     */
    @Test
    void explicitScopeIsPreserved() {
        Role role = Role.builder().id("patient_chat").name("患者聊天").scope("PATIENT").build();
        role.onCreate();
        assertEquals("PATIENT", role.getScope());
    }
}
