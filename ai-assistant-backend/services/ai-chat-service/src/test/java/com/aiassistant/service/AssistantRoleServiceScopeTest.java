package com.aiassistant.service;

import com.aiassistant.config.AiProperties;
import com.aiassistant.dto.RoleForm;
import com.aiassistant.entity.Role;
import com.aiassistant.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 角色管理按域/按院过滤、患者角色派生 ID 与权限边界单测
 *
 * @author yh
 * @date 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
class AssistantRoleServiceScopeTest {

    @Mock private RoleRepository roleRepository;
    @Mock private PlatformTransactionManager txManager;
    private AssistantRoleService service;

    @BeforeEach
    void setUp() {
        lenient().when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new AssistantRoleService(roleRepository, new AiProperties(), txManager);
    }

    @Test
    void listsStaffRolesByScope() {
        when(roleRepository.findByScopeOrderBySortOrderAscIdAsc("STAFF")).thenReturn(List.of());
        assertTrue(service.listByScope("STAFF", null).isEmpty());
        verify(roleRepository, never()).save(any());
    }

    @Test
    void listsPatientRolesByHospitalEnsuringDefaults() {
        stubExistingPatientRoles(1L);
        when(roleRepository.findByScopeAndHospitalIdOrderBySortOrderAscIdAsc("PATIENT", 1L))
                .thenReturn(List.of(patientRole(1L, "CHAT")));

        assertEquals(1, service.listByScope("PATIENT", 1L).size());
        for (String type : List.of("CHAT", "TRIAGE", "RAG")) {
            verify(roleRepository).findByScopeAndHospitalIdAndSessionType("PATIENT", 1L, type);
        }
        assertThrows(IllegalArgumentException.class, () -> service.listByScope("PATIENT", null));
    }

    @Test
    void patientListCreatesMissingDefaultsForAllTypes() {
        when(roleRepository.findByScopeAndHospitalIdAndSessionType(eq("PATIENT"), eq(1L), anyString()))
                .thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.findByScopeAndHospitalIdOrderBySortOrderAscIdAsc("PATIENT", 1L))
                .thenReturn(List.of());

        service.listByScope("PATIENT", 1L);

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, times(3)).save(captor.capture());
        assertEquals(List.of("pat_1_CHAT", "pat_1_TRIAGE", "pat_1_RAG"),
                captor.getAllValues().stream().map(Role::getId).toList());
        for (String type : List.of("CHAT", "TRIAGE", "RAG")) {
            verify(roleRepository).findByScopeAndHospitalIdAndSessionType("PATIENT", 1L, type);
        }
    }

    @Test
    void createsPatientRoleWithDerivedIdAndForcedHospital() {
        RoleForm form = new RoleForm();
        form.setId("pat_99_TRIAGE"); // 客户端 ID 应被忽略
        form.setName("导诊");
        form.setSystemPrompt("prompt");
        form.setScope("PATIENT");
        form.setSessionType("triage");
        form.setHospitalId(99L);   // 应被服务端医院覆盖
        when(roleRepository.findByScopeAndHospitalIdAndSessionType("PATIENT", 1L, "TRIAGE"))
                .thenReturn(Optional.empty());
        when(roleRepository.existsById("pat_1_TRIAGE")).thenReturn(false);
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role saved = service.createForScope(form, 1L);

        assertEquals("pat_1_TRIAGE", saved.getId());
        assertEquals(1L, saved.getHospitalId());
        assertEquals("PATIENT", saved.getScope());
        assertEquals("TRIAGE", saved.getSessionType());
    }

    @Test
    void rejectsDuplicatePatientCreate() {
        RoleForm form = new RoleForm();
        form.setName("导诊");
        form.setSystemPrompt("prompt");
        form.setScope("PATIENT");
        form.setSessionType("TRIAGE");
        when(roleRepository.findByScopeAndHospitalIdAndSessionType("PATIENT", 1L, "TRIAGE"))
                .thenReturn(Optional.of(patientRole(1L, "TRIAGE")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createForScope(form, 1L));
        assertEquals("该医院该会话类型已存在角色", ex.getMessage());
        verify(roleRepository, never()).save(any());
    }

    @Test
    void rejectsBoundOperatorStaffCreate() {
        RoleForm form = new RoleForm();
        form.setId("staff_extra");
        form.setName("员工角色");
        form.setSystemPrompt("prompt");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createForScope(form, 1L));
        assertEquals("员工角色仅平台管理员可维护", ex.getMessage());
        verify(roleRepository, never()).save(any());
    }

    @Test
    void allowsUnboundOperatorStaffCreate() {
        RoleForm form = new RoleForm();
        form.setId("staff_extra");
        form.setName("员工角色");
        form.setSystemPrompt("prompt");
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role saved = service.createForScope(form, null);

        assertEquals("staff_extra", saved.getId());
        assertEquals("STAFF", saved.getScope());
    }

    @Test
    void rejectsBoundOperatorStaffUpdate() {
        when(roleRepository.findById("staff_a")).thenReturn(Optional.of(staffRole("staff_a")));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateForScope("staff_a", new RoleForm(), 1L));
        verify(roleRepository, never()).save(any());
    }

    @Test
    void rejectsBoundOperatorStaffDelete() {
        when(roleRepository.findById("staff_a")).thenReturn(Optional.of(staffRole("staff_a")));

        assertThrows(IllegalArgumentException.class, () -> service.deleteForScope("staff_a", 1L));
        verify(roleRepository, never()).delete(any());
    }

    @Test
    void rejectsCrossHospitalPatientUpdate() {
        when(roleRepository.findById("pat_2_CHAT")).thenReturn(Optional.of(patientRole(2L, "CHAT")));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateForScope("pat_2_CHAT", new RoleForm(), 1L));
        verify(roleRepository, never()).save(any());
    }

    @Test
    void rejectsCrossHospitalPatientDelete() {
        when(roleRepository.findById("pat_2_CHAT")).thenReturn(Optional.of(patientRole(2L, "CHAT")));

        assertThrows(IllegalArgumentException.class, () -> service.deleteForScope("pat_2_CHAT", 1L));
        verify(roleRepository, never()).delete(any());
    }

    @Test
    void allowsSameHospitalPatientDelete() {
        Role role = patientRole(1L, "CHAT");
        when(roleRepository.findById("pat_1_CHAT")).thenReturn(Optional.of(role));

        service.deleteForScope("pat_1_CHAT", 1L);

        verify(roleRepository).delete(role);
    }

    @Test
    void allowsUnboundOperatorPatientDelete() {
        Role role = patientRole(2L, "CHAT");
        when(roleRepository.findById("pat_2_CHAT")).thenReturn(Optional.of(role));

        service.deleteForScope("pat_2_CHAT", null);

        verify(roleRepository).delete(role);
    }

    @Test
    void rejectsCrossHospitalPatientDetail() {
        when(roleRepository.findById("pat_2_CHAT")).thenReturn(Optional.of(patientRole(2L, "CHAT")));

        assertThrows(IllegalArgumentException.class, () -> service.findByIdForScope("pat_2_CHAT", 1L));
    }

    @Test
    void allowsSameHospitalPatientDetail() {
        when(roleRepository.findById("pat_1_CHAT")).thenReturn(Optional.of(patientRole(1L, "CHAT")));

        assertTrue(service.findByIdForScope("pat_1_CHAT", 1L).isPresent());
    }

    @Test
    void resolveRoleIdDoesNotResolvePatientRole() {
        Role patient = patientRole(1L, "TRIAGE");
        patient.setEnabled(true);
        Role staff = staffRole("chat_buddy");
        when(roleRepository.findById("pat_1_TRIAGE")).thenReturn(Optional.of(patient));
        when(roleRepository.findByScopeAndEnabledTrueOrderBySortOrderAscIdAsc("STAFF"))
                .thenReturn(List.of(staff));

        assertEquals("chat_buddy", service.resolveRoleId("pat_1_TRIAGE"));
        verify(roleRepository).findByScopeAndEnabledTrueOrderBySortOrderAscIdAsc("STAFF");
    }

    /**
     * 为三类默认患者角色返回已存在记录，避免列表触发建库
     *
     * @param hospitalId 医院 ID
     */
    private void stubExistingPatientRoles(Long hospitalId) {
        for (String type : List.of("CHAT", "TRIAGE", "RAG")) {
            when(roleRepository.findByScopeAndHospitalIdAndSessionType("PATIENT", hospitalId, type))
                    .thenReturn(Optional.of(patientRole(hospitalId, type)));
        }
    }

    /**
     * 构造一个患者角色
     *
     * @param hospitalId 所属医院 ID
     * @param sessionType 会话类型
     * @return 患者角色实体
     */
    private Role patientRole(Long hospitalId, String sessionType) {
        return Role.builder()
                .id("pat_" + hospitalId + "_" + sessionType)
                .name("患者角色")
                .scope("PATIENT")
                .sessionType(sessionType)
                .hospitalId(hospitalId)
                .build();
    }

    /**
     * 构造一个员工角色
     *
     * @param id 角色 ID
     * @return 员工角色实体
     */
    private Role staffRole(String id) {
        return Role.builder()
                .id(id)
                .name("员工角色")
                .scope("STAFF")
                .build();
    }
}
