package com.aiassistant.common.support;

import com.aiassistant.system.mapper.SysUserRoleMapper;

import java.util.List;

/**
 * 用户-角色绑定支撑
 *
 * @author yh
 * @date 2026-10-10
 */
public final class UserRoleBinder {

    private UserRoleBinder() {
    }

    /**
     * 重建用户角色关联：先清空，再按 roleIds 逐条插入（为空仅清空）
     *
     * @param userRoleMapper 用户角色关联 Mapper
     * @param userId 用户 ID
     * @param roleIds 角色 ID 集合（可空）
     */
    public static void bind(SysUserRoleMapper userRoleMapper, Long userId, List<Long> roleIds) {
        userRoleMapper.deleteByUserId(userId);
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            userRoleMapper.insertRelation(userId, roleId);
        }
    }
}
