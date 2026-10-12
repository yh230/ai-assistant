package com.aiassistant.common.constant;

/**
 * 角色码常量（RBAC 角色词汇表）
 *
 * @author yh
 * @date 2026-10-10
 */
public final class RoleConstants {

    private RoleConstants() {
    }

    /** 超级管理员 */
    public static final String ADMIN = "admin";

    /** 院长 */
    public static final String HOSPITAL_DIRECTOR = "hospital_director";

    /** 科室主任 */
    public static final String DEPT_DIRECTOR = "dept_director";

    /** 医生 */
    public static final String DOCTOR = "doctor";

    /** 护士 */
    public static final String NURSE = "nurse";

    /** 护工 */
    public static final String CAREGIVER = "caregiver";

    /** 门诊管理员 */
    public static final String CLINIC_ADMIN = "clinic_admin";

    /** 收费窗口操作员 */
    public static final String CASHIER = "cashier";

    /** 自助机终端 */
    public static final String SELF_SERVICE_KIOSK = "self_service_kiosk";
}
