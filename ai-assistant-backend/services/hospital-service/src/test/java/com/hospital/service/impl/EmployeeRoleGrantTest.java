package com.hospital.service.impl;

import com.api.client.UserClient;
import com.api.dto.RoleBriefDTO;
import com.api.dto.UserBriefDTO;
import com.common.auth.UserSnapshot;
import com.common.constant.RoleConstants;
import com.common.exception.BusinessException;
import com.common.result.Result;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.hospital.dto.EmployeeSaveDTO;
import com.hospital.dto.EmployeeUpdateDTO;
import com.hospital.mapper.DepartmentMapper;
import com.hospital.mapper.HospitalMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 医院员工角色授予白名单测试
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class EmployeeRoleGrantTest {

    @Mock private UserClient userClient;
    @Mock private HospitalMapper hospitalMapper;
    @Mock private DepartmentMapper departmentMapper;
    @Mock private DataScopeResolver dataScopeResolver;
    @InjectMocks private EmployeeServiceImpl employeeService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsAdminRoleGrantedByHospitalDirector() {
        login(1L, RoleConstants.HOSPITAL_DIRECTOR);
        when(dataScopeResolver.resolve()).thenReturn(DataScope.hospital(10L));
        when(userClient.roles()).thenReturn(Result.success(List.of(
                role(1L, RoleConstants.ADMIN), role(2L, RoleConstants.DOCTOR))));
        EmployeeSaveDTO dto = new EmployeeSaveDTO();
        dto.setRoleIds(List.of(1L));

        assertThrows(BusinessException.class, () -> employeeService.create(dto));
        verify(userClient, never()).create(any());
    }

    @Test
    void createRejectsClinicAdminRoleGrantedByDeptDirector() {
        login(2L, RoleConstants.DEPT_DIRECTOR);
        when(dataScopeResolver.resolve()).thenReturn(DataScope.department(20L, 10L));
        when(userClient.roles()).thenReturn(Result.success(List.of(
                role(3L, RoleConstants.CLINIC_ADMIN), role(2L, RoleConstants.DOCTOR))));
        EmployeeSaveDTO dto = new EmployeeSaveDTO();
        dto.setRoleIds(List.of(3L));

        assertThrows(BusinessException.class, () -> employeeService.create(dto));
        verify(userClient, never()).create(any());
    }

    @Test
    void createAllowsAdminGrantingAdmin() {
        login(9L, RoleConstants.ADMIN);
        when(dataScopeResolver.resolve()).thenReturn(DataScope.all());
        when(userClient.roles()).thenReturn(Result.success(List.of(role(1L, RoleConstants.ADMIN))));
        when(userClient.create(any())).thenReturn(Result.success(100L));
        EmployeeSaveDTO dto = new EmployeeSaveDTO();
        dto.setRoleIds(List.of(1L));

        assertDoesNotThrow(() -> employeeService.create(dto));
        verify(userClient).create(dto);
    }

    @Test
    void createRejectsUnknownRoleId() {
        login(1L, RoleConstants.HOSPITAL_DIRECTOR);
        when(dataScopeResolver.resolve()).thenReturn(DataScope.hospital(10L));
        when(userClient.roles()).thenReturn(Result.success(List.of(role(2L, RoleConstants.DOCTOR))));
        EmployeeSaveDTO dto = new EmployeeSaveDTO();
        dto.setRoleIds(List.of(99L));

        assertThrows(BusinessException.class, () -> employeeService.create(dto));
        verify(userClient, never()).create(any());
    }

    @Test
    void updateRejectsNonAdminChangingOwnRoles() {
        login(5L, RoleConstants.HOSPITAL_DIRECTOR);
        UserBriefDTO self = new UserBriefDTO();
        self.setId(5L);
        self.setHospitalId(10L);
        when(userClient.batch(any())).thenReturn(Result.success(List.of(self)));
        when(dataScopeResolver.resolve()).thenReturn(DataScope.all());
        EmployeeUpdateDTO dto = new EmployeeUpdateDTO();
        dto.setRoleIds(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.update(5L, dto));
        assertEquals("不能修改自己的角色", ex.getMessage());
        verify(userClient, never()).update(any(), any());
    }

    @Test
    void updateRejectsRoleEscalation() {
        login(1L, RoleConstants.HOSPITAL_DIRECTOR);
        UserBriefDTO target = new UserBriefDTO();
        target.setId(6L);
        target.setHospitalId(10L);
        when(userClient.batch(any())).thenReturn(Result.success(List.of(target)));
        when(dataScopeResolver.resolve()).thenReturn(DataScope.all());
        when(userClient.roles()).thenReturn(Result.success(List.of(
                role(1L, RoleConstants.ADMIN), role(2L, RoleConstants.DOCTOR))));
        EmployeeUpdateDTO dto = new EmployeeUpdateDTO();
        dto.setRoleIds(List.of(1L));

        assertThrows(BusinessException.class, () -> employeeService.update(6L, dto));
        verify(userClient, never()).update(any(), any());
    }

    /**
     * 写入当前登录人快照（roles 非空）
     *
     * @param userId 登录人 ID
     * @param roles 角色编码
     */
    private void login(Long userId, String... roles) {
        UserSnapshot snapshot = UserSnapshot.builder().userId(userId).roles(List.of(roles)).build();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("tester", "n/a", List.of());
        authentication.setDetails(snapshot);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 构造角色简要信息
     *
     * @param id 角色 ID
     * @param code 角色编码
     * @return 角色简要信息
     */
    private static RoleBriefDTO role(Long id, String code) {
        RoleBriefDTO dto = new RoleBriefDTO();
        dto.setId(id);
        dto.setRoleCode(code);
        return dto;
    }
}
