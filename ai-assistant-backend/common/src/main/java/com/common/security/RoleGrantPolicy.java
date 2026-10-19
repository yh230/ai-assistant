package com.common.security;

import com.common.constant.RoleConstants;
import com.common.exception.BusinessException;

import java.util.List;
import java.util.Set;

/**
 * 角色授予策略：按操作者角色收敛可授予集合，禁止非 admin 授予 admin
 * <p>
 * 授予规则：
 * <ul>
 *     <li>admin：可授予任意角色</li>
 *     <li>hospital_director：除 admin 外的全部角色</li>
 *     <li>clinic_admin：门诊岗位角色（医生/护士/护工/收费员/自助机）</li>
 *     <li>dept_director：仅医生/护士/护工</li>
 *     <li>其他角色：不可授予任何角色</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-19
 */
public final class RoleGrantPolicy {

    /** 临床岗位角色 */
    private static final Set<String> CLINICAL = Set.of(
            RoleConstants.DOCTOR, RoleConstants.NURSE, RoleConstants.CAREGIVER);

    /** 门诊岗位角色 */
    private static final Set<String> CLINIC = Set.of(
            RoleConstants.DOCTOR, RoleConstants.NURSE, RoleConstants.CAREGIVER,
            RoleConstants.CASHIER, RoleConstants.SELF_SERVICE_KIOSK);

    private RoleGrantPolicy() {
    }

    /**
     * 判断操作者是否可授予目标角色
     *
     * @param operatorRoles 操作者角色编码列表
     * @param targetRoleCode 目标角色编码
     * @return true 表示允许授予
     */
    public static boolean canGrant(List<String> operatorRoles, String targetRoleCode) {
        if (operatorRoles == null || targetRoleCode == null) {
            return false;
        }
        if (operatorRoles.contains(RoleConstants.ADMIN)) {
            return true;
        }
        // 非 admin 一律不得授予 admin（防止提权）
        if (RoleConstants.ADMIN.equals(targetRoleCode)) {
            return false;
        }
        if (operatorRoles.contains(RoleConstants.HOSPITAL_DIRECTOR)) {
            return true;
        }
        if (operatorRoles.contains(RoleConstants.CLINIC_ADMIN)) {
            return CLINIC.contains(targetRoleCode);
        }
        if (operatorRoles.contains(RoleConstants.DEPT_DIRECTOR)) {
            return CLINICAL.contains(targetRoleCode);
        }
        return false;
    }

    /**
     * 校验操作者可授予全部目标角色，任一越权即抛业务异常
     *
     * @param operatorRoles 操作者角色编码列表
     * @param targetRoleCodes 目标角色编码列表（空表示不调整角色）
     */
    public static void assertGrantable(List<String> operatorRoles, List<String> targetRoleCodes) {
        if (targetRoleCodes == null || targetRoleCodes.isEmpty()) {
            return;
        }
        for (String code : targetRoleCodes) {
            if (!canGrant(operatorRoles, code)) {
                throw new BusinessException("无权授予角色：" + code);
            }
        }
    }
}
