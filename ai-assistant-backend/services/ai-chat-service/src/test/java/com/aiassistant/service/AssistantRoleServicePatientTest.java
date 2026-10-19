package com.aiassistant.service;

import com.aiassistant.config.AiProperties;
import com.aiassistant.entity.Role;
import com.aiassistant.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 患者角色惰性建库与提示词解析单测
 *
 * @author yh
 * @date 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
class AssistantRoleServicePatientTest {

    @Mock private RoleRepository roleRepository;
    private AiProperties aiProperties;
    private AssistantRoleService service;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getPrompt().setTriage("导诊模板");
        service = new AssistantRoleService(roleRepository, aiProperties);
    }

    @Test
    void createsMissingPatientRoleFromTemplate() {
        when(roleRepository.findByScopeAndHospitalIdAndSessionType("PATIENT", 1L, "TRIAGE"))
                .thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role role = service.resolvePatientRole(1L, "TRIAGE");

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(captor.capture());
        Role saved = captor.getValue();
        assertEquals("pat_1_TRIAGE", saved.getId());
        assertEquals("PATIENT", saved.getScope());
        assertEquals(1L, saved.getHospitalId());
        assertEquals("TRIAGE", saved.getSessionType());
        assertEquals("导诊模板", saved.getSystemPrompt());
        assertTrue(saved.getEnabled());
        assertEquals("pat_1_TRIAGE", role.getId());
    }

    @Test
    void returnsExistingEnabledRoleWithoutCreating() {
        Role existing = Role.builder().id("pat_1_TRIAGE").scope("PATIENT").hospitalId(1L)
                .sessionType("TRIAGE").systemPrompt("库内导诊").enabled(true).build();
        when(roleRepository.findByScopeAndHospitalIdAndSessionType("PATIENT", 1L, "TRIAGE"))
                .thenReturn(Optional.of(existing));

        assertEquals("库内导诊", service.resolvePatientSystemPrompt(1L, "TRIAGE"));
        verify(roleRepository, never()).save(any());
    }

    @Test
    void fallsBackToTemplateWhenDisabled() {
        Role disabled = Role.builder().id("pat_1_TRIAGE").scope("PATIENT").hospitalId(1L)
                .sessionType("TRIAGE").systemPrompt("库内导诊").enabled(false).build();
        when(roleRepository.findByScopeAndHospitalIdAndSessionType("PATIENT", 1L, "TRIAGE"))
                .thenReturn(Optional.of(disabled));

        assertEquals("导诊模板", service.resolvePatientSystemPrompt(1L, "TRIAGE"));
    }

    @Test
    void returnsNullRoleAndTemplateWhenHospitalMissing() {
        assertNull(service.resolvePatientRole(null, "TRIAGE"));
        assertEquals("导诊模板", service.resolvePatientSystemPrompt(null, "TRIAGE"));
        verifyNoInteractions(roleRepository);
    }
}
