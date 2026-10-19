package com.common.security;

import com.common.constant.RoleConstants;
import com.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色授予策略测试
 *
 * @author yh
 * @date 2026-10-19
 */
class RoleGrantPolicyTest {

    @Test
    void nonAdminCannotGrantAdmin() {
        assertFalse(RoleGrantPolicy.canGrant(List.of(RoleConstants.HOSPITAL_DIRECTOR), RoleConstants.ADMIN));
        assertFalse(RoleGrantPolicy.canGrant(List.of(RoleConstants.CLINIC_ADMIN), RoleConstants.ADMIN));
        assertThrows(BusinessException.class, () -> RoleGrantPolicy.assertGrantable(
                List.of(RoleConstants.HOSPITAL_DIRECTOR), List.of(RoleConstants.ADMIN)));
    }

    @Test
    void deptDirectorOnlyGrantsClinicalRoles() {
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.DEPT_DIRECTOR), RoleConstants.DOCTOR));
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.DEPT_DIRECTOR), RoleConstants.NURSE));
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.DEPT_DIRECTOR), RoleConstants.CAREGIVER));
        assertFalse(RoleGrantPolicy.canGrant(List.of(RoleConstants.DEPT_DIRECTOR), RoleConstants.CLINIC_ADMIN));
        assertFalse(RoleGrantPolicy.canGrant(List.of(RoleConstants.DEPT_DIRECTOR), RoleConstants.DEPT_DIRECTOR));
    }

    @Test
    void hospitalDirectorGrantsAnyRoleExceptAdmin() {
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.HOSPITAL_DIRECTOR), RoleConstants.DOCTOR));
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.HOSPITAL_DIRECTOR), RoleConstants.DEPT_DIRECTOR));
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.HOSPITAL_DIRECTOR), RoleConstants.CLINIC_ADMIN));
    }

    @Test
    void clinicAdminOnlyGrantsClinicRoles() {
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.CLINIC_ADMIN), RoleConstants.DOCTOR));
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.CLINIC_ADMIN), RoleConstants.CASHIER));
        assertFalse(RoleGrantPolicy.canGrant(List.of(RoleConstants.CLINIC_ADMIN), RoleConstants.DEPT_DIRECTOR));
    }

    @Test
    void adminGrantsEverything() {
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.ADMIN), RoleConstants.ADMIN));
        assertTrue(RoleGrantPolicy.canGrant(List.of(RoleConstants.ADMIN), RoleConstants.HOSPITAL_DIRECTOR));
        assertDoesNotThrow(() -> RoleGrantPolicy.assertGrantable(
                List.of(RoleConstants.ADMIN), List.of(RoleConstants.ADMIN, RoleConstants.DEPT_DIRECTOR)));
    }

    @Test
    void ordinaryEmployeeGrantsNothing() {
        assertFalse(RoleGrantPolicy.canGrant(List.of(RoleConstants.NURSE), RoleConstants.DOCTOR));
        assertFalse(RoleGrantPolicy.canGrant(null, RoleConstants.DOCTOR));
        assertFalse(RoleGrantPolicy.canGrant(List.of(RoleConstants.NURSE), null));
    }

    @Test
    void emptyTargetRolesAreNoop() {
        assertDoesNotThrow(() -> RoleGrantPolicy.assertGrantable(List.of(RoleConstants.NURSE), List.of()));
        assertDoesNotThrow(() -> RoleGrantPolicy.assertGrantable(List.of(RoleConstants.NURSE), null));
    }
}
