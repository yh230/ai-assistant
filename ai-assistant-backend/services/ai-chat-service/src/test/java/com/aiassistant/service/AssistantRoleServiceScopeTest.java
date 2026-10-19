package com.aiassistant.service;

import com.aiassistant.config.AiProperties;
import com.aiassistant.dto.RoleForm;
import com.aiassistant.entity.Role;
import com.aiassistant.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 角色管理按域/按院过滤单测
 *
 * @author yh
 * @date 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
class AssistantRoleServiceScopeTest {

    @Mock private RoleRepository roleRepository;
    private AssistantRoleService service;

    @BeforeEach
    void setUp() {
        service = new AssistantRoleService(roleRepository, new AiProperties(), mock(PlatformTransactionManager.class));
    }

    @Test
    void listsStaffRolesByScope() {
        when(roleRepository.findByScopeOrderBySortOrderAscIdAsc("STAFF")).thenReturn(List.of());
        assertTrue(service.listByScope("STAFF", null).isEmpty());
    }

    @Test
    void listsPatientRolesByHospital() {
        when(roleRepository.findByScopeAndHospitalIdOrderBySortOrderAscIdAsc("PATIENT", 1L)).thenReturn(List.of());
        assertTrue(service.listByScope("PATIENT", 1L).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.listByScope("PATIENT", null));
    }

    @Test
    void createsPatientRoleWithForcedHospital() {
        RoleForm form = new RoleForm();
        form.setId("pat_1_TRIAGE");
        form.setName("导诊");
        form.setSystemPrompt("prompt");
        form.setScope("PATIENT");
        form.setSessionType("TRIAGE");
        form.setHospitalId(99L);   // 应被服务端医院覆盖
        when(roleRepository.existsById("pat_1_TRIAGE")).thenReturn(false);
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role saved = service.createForScope(form, 1L);

        assertEquals(1L, saved.getHospitalId());
        assertEquals("PATIENT", saved.getScope());
        assertEquals("TRIAGE", saved.getSessionType());
    }
}
