package com.system.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.system.entity.SysRole;
import com.system.entity.SysUserRole;
import com.system.mapper.SysRoleMapper;
import com.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户角色批量加载：一次分页内固定两条查询，消除逐行查询角色的 N+1
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
@RequiredArgsConstructor
public class UserRoleBatchLoader {

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;

    /**
     * 批量加载用户角色（用户角色关联查一次，角色信息按 ID 批量查一次）
     *
     * @param userIds 用户 ID 列表（可空）
     * @return 用户 ID -> 角色视图；无角色关联的用户不在结果中
     */
    public Map<Long, RoleView> load(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctUserIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctUserIds.isEmpty()) {
            return Map.of();
        }
        List<SysUserRole> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, distinctUserIds));
        if (relations == null || relations.isEmpty()) {
            return Map.of();
        }
        Set<Long> roleIds = relations.stream()
                .map(SysUserRole::getRoleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysRole> roles = roleIds.isEmpty() ? Map.of()
                : nullSafe(roleMapper.selectBatchIds(roleIds)).stream()
                        .collect(Collectors.toMap(SysRole::getId, r -> r));
        Map<Long, List<Long>> idsByUser = relations.stream()
                .filter(r -> r.getUserId() != null)
                .collect(Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(SysUserRole::getRoleId, Collectors.toList())));
        Map<Long, RoleView> result = new HashMap<>();
        idsByUser.forEach((userId, ids) -> result.put(userId, new RoleView(ids,
                ids.stream().map(roles::get).filter(Objects::nonNull)
                        .map(SysRole::getRoleName).collect(Collectors.joining(",")))));
        return result;
    }

    /**
     * 角色视图：角色 ID 列表与逗号拼接的角色名称
     *
     * @param roleIds 角色 ID 列表
     * @param roleNames 角色名称（逗号分隔）
     */
    public record RoleView(List<Long> roleIds, String roleNames) {
    }

    /**
     * 空集合兜底
     *
     * @param roles 角色列表（可空）
     * @return 非空列表
     */
    private static List<SysRole> nullSafe(List<SysRole> roles) {
        return roles == null ? List.of() : roles;
    }
}
