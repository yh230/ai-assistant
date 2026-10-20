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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 员工数据范围与管理员目标保护回归
 * <p>
 * 覆盖：按 id 读取的跨院拒绝；非 admin 对管理员目标的删除/禁用/重置密码拦截
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceImplAdminTargetTest {

    private static final Long ADMIN_ROLE_ID = 1L;
    private static final Long DOCTOR_ROLE_ID = 2L;

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
    void getRejectsEmployeeOutOfScope() {
        // 院长仅可访问本院（1），目标员工属于医院 2
        login(10L, RoleConstants.HOSPITAL_DIRECTOR);
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.hospital(1L));
        when(userClient.batch(List.of(6L))).thenReturn(Result.success(List.of(user(6L, 2L, DOCTOR_ROLE_ID))));

        BusinessException ex = assertThrows(BusinessException.class, () -> employeeService.get(6L));
        assertEquals("无权查看该员工", ex.getMessage());
    }

    @Test
    void deleteRejectsAdminTargetForNonAdmin() {
        login(10L, RoleConstants.HOSPITAL_DIRECTOR);
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.all());
        when(userClient.batch(List.of(6L))).thenReturn(Result.success(List.of(user(6L, 10L, ADMIN_ROLE_ID))));
        when(userClient.roles()).thenReturn(Result.success(List.of(role(ADMIN_ROLE_ID, RoleConstants.ADMIN))));

        BusinessException ex = assertThrows(BusinessException.class, () -> employeeService.delete(6L));
        assertEquals("无权操作管理员账号", ex.getMessage());
        verify(userClient, never()).delete(anyLong());
    }

    @Test
    void changeStatusRejectsAdminTargetForNonAdmin() {
        login(10L, RoleConstants.HOSPITAL_DIRECTOR);
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.all());
        when(userClient.batch(List.of(6L))).thenReturn(Result.success(List.of(user(6L, 10L, ADMIN_ROLE_ID))));
        when(userClient.roles()).thenReturn(Result.success(List.of(role(ADMIN_ROLE_ID, RoleConstants.ADMIN))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.changeStatus(6L, 0));
        assertEquals("无权操作管理员账号", ex.getMessage());
        verify(userClient, never()).update(anyLong(), any());
    }

    @Test
    void resetPasswordRejectsAdminTargetForNonAdmin() {
        login(10L, RoleConstants.HOSPITAL_DIRECTOR);
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.all());
        when(userClient.batch(List.of(6L))).thenReturn(Result.success(List.of(user(6L, 10L, ADMIN_ROLE_ID))));
        when(userClient.roles()).thenReturn(Result.success(List.of(role(ADMIN_ROLE_ID, RoleConstants.ADMIN))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.resetPassword(6L, "NewPass123"));
        assertEquals("无权操作管理员账号", ex.getMessage());
        verify(userClient, never()).update(anyLong(), any());
    }

    @Test
    void updateRejectsAdminTargetWhenRoleIdsAbsent() {
        // 关键回归：不携带 roleIds 的改密/资料更新同样不得触碰管理员目标
        login(10L, RoleConstants.HOSPITAL_DIRECTOR);
        when(dataScopeResolver.resolveRequired()).thenReturn(DataScope.all());
        when(userClient.batch(List.of(6L))).thenReturn(Result.success(List.of(user(6L, 10L, ADMIN_ROLE_ID))));
        when(userClient.roles()).thenReturn(Result.success(List.of(role(ADMIN_ROLE_ID, RoleConstants.ADMIN))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> employeeService.update(6L, new EmployeeUpdateDTO()));
        assertEquals("无权修改管理员账号", ex.getMessage());
        verify(userClient, never()).update(anyLong(), any());
    }

    /**
     * 写入当前登录人快照
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
     * 构造员工简要信息
     *
     * @param id 用户 ID
     * @param hospitalId 所属医院 ID
     * @param roleId 当前角色 ID
     * @return 员工简要信息
     */
    private static UserBriefDTO user(Long id, Long hospitalId, Long roleId) {
        UserBriefDTO dto = new UserBriefDTO();
        dto.setId(id);
        dto.setHospitalId(hospitalId);
        dto.setRoleIds(List.of(roleId));
        return dto;
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
