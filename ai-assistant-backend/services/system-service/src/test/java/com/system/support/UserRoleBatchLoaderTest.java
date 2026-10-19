package com.system.support;

import com.system.entity.SysRole;
import com.system.entity.SysUserRole;
import com.system.mapper.SysRoleMapper;
import com.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户角色批量加载测试
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class UserRoleBatchLoaderTest {

    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysRoleMapper roleMapper;
    @InjectMocks private UserRoleBatchLoader loader;

    @Test
    void loadsRolesInTwoQueries() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                relation(1L, 10L), relation(2L, 11L)));
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(
                role(10L, "医生"), role(11L, "护士")));

        Map<Long, UserRoleBatchLoader.RoleView> result = loader.load(List.of(1L, 2L));

        assertEquals(List.of(10L), result.get(1L).roleIds());
        assertEquals("医生", result.get(1L).roleNames());
        assertEquals(List.of(11L), result.get(2L).roleIds());
        assertEquals("护士", result.get(2L).roleNames());
        // 固定两条查询：用户角色关联一次，角色信息按 ID 批量一次
        verify(userRoleMapper, times(1)).selectList(any());
        verify(roleMapper, times(1)).selectBatchIds(any());
        // 不再逐行查询角色
        verify(userRoleMapper, never()).selectRoleIdsByUserId(anyLong());
        verify(roleMapper, never()).selectByUserId(anyLong());
    }

    @Test
    void emptyInputShortCircuits() {
        assertTrue(loader.load(null).isEmpty());
        assertTrue(loader.load(List.of()).isEmpty());

        verifyNoInteractions(userRoleMapper, roleMapper);
    }

    private static SysUserRole relation(Long userId, Long roleId) {
        SysUserRole relation = new SysUserRole();
        relation.setUserId(userId);
        relation.setRoleId(roleId);
        return relation;
    }

    private static SysRole role(Long id, String roleName) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleName(roleName);
        return role;
    }
}
