package com.aiassistant.common.security;

import com.aiassistant.common.constant.RoleConstants;
import com.aiassistant.security.SecurityUtils;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysRoleMapper;
import com.aiassistant.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据权限解析器（医院/挂号模块唯一解析器）
 * <p>
 * 变更点：原 hospital 与 registration 两套解析器合并上移（删 RegistrationDataScopeResolver）
 * <p>
 * 变更点（由 Sa-Token 迁移）：登录态判定与当前登录人改经 {@link SecurityUtils} 从 SecurityContext 获取
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
@Slf4j
@Component
@RequiredArgsConstructor
public class DataScopeResolver {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;

    /**
     * 解析当前登录人的数据权限范围
     *
     * @return 数据权限范围
     */
    public DataScope resolve() {
        // 变更点（由 Sa-Token 迁移）：未登录（启动播种/定时任务/内部调用）→ ALL
        if (!SecurityUtils.isAuthenticated()) {
            return DataScope.all();
        }
        Long userId = SecurityUtils.getCurrentUserId();
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        if (roles == null) {
            roles = List.of();
        }
        if (roles.contains(RoleConstants.ADMIN)) {
            return DataScope.all();
        }
        SysUser user = userMapper.selectById(userId);
        Long hospitalId = user == null ? null : user.getHospitalId();
        Long deptId = user == null ? null : user.getDepartmentId();

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
        return DataScope.self(userId);
    }
}
