package com.common.security;

import com.common.auth.UserSnapshot;
import com.common.constant.RoleConstants;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据权限解析器（医院/挂号模块唯一解析器）
 * <p>
 * 登录态判定与当前登录人经 {@link SecurityUtils} 从 SecurityContext 获取
 * 角色优先级：admin > 院长 > 科室主任 > 门诊角色 > 普通员工
 * <ul>
 *     <li>{@code admin} → ALL：跨院所有数据</li>
 *     <li>{@code hospital_director} → HOSPITAL：仅本人所属医院</li>
 *     <li>{@code dept_director} → DEPARTMENT：仅本科室</li>
 *     <li>{@code clinic_admin}/{@code cashier}/{@code self_service_kiosk} → HOSPITAL：仅本人所属医院</li>
 *     <li>其余（医生/护士/护工等）→ SELF：仅本人</li>
 *     <li>未登录（启动播种/定时任务/内部调用）→ ALL</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-12
 */
@Component
public class DataScopeResolver {

    /**
     * 解析当前登录人的数据权限范围
     *
     * @return 数据权限范围
     */
    public DataScope resolve() {
        // 未登录（启动播种/定时任务/内部调用）→ ALL
        UserSnapshot snapshot = SecurityUtils.currentSnapshot();
        if (snapshot == null) {
            return DataScope.all();
        }
        List<String> roles = snapshot.getRoles();
        if (roles == null) {
            roles = List.of();
        }
        if (roles.contains(RoleConstants.ADMIN)) {
            return DataScope.all();
        }
        Long hospitalId = snapshot.getHospitalId();
        Long deptId = snapshot.getDepartmentId();

        if (roles.contains(RoleConstants.HOSPITAL_DIRECTOR)) {
            return DataScope.hospital(hospitalId);
        }
        if (roles.contains(RoleConstants.DEPT_DIRECTOR)) {
            return DataScope.department(deptId, hospitalId);
        }
        if (roles.contains(RoleConstants.CLINIC_ADMIN) || roles.contains(RoleConstants.CASHIER)
                || roles.contains(RoleConstants.SELF_SERVICE_KIOSK)) {
            return DataScope.hospital(hospitalId);
        }
        return DataScope.self(snapshot.getUserId());
    }
}
