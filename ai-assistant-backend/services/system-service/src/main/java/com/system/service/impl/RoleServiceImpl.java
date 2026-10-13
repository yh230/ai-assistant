package com.system.service.impl;

import com.api.client.AuthClient;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.system.dto.AssignMenuDTO;
import com.system.dto.RoleSaveDTO;
import com.system.entity.SysRole;
import com.system.entity.SysRoleMenu;
import com.system.mapper.SysRoleMapper;
import com.system.mapper.SysRoleMenuMapper;
import com.system.mapper.SysUserRoleMapper;
import com.system.service.RoleService;
import com.system.vo.RoleVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色服务实现
 * <p>
 * 角色删除/菜单权限分配后经 {@link AuthClient} 失效该角色下用户的权限快照
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final AuthClient authClient;

    /**
     * 分页查询角色列表
     *
     * @param keyword 关键字（角色名/编码，可空）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 角色分页结果
     */
    @Override
    public PageResult<RoleVO> pageRoles(String keyword, long pageNum, long pageSize) {
        Page<SysRole> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysRole> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.like(SysRole::getRoleName, keyword).or().like(SysRole::getRoleCode, keyword);
        }
        qw.orderByDesc(SysRole::getId);
        roleMapper.selectPage(page, qw);
        List<RoleVO> list = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(page, list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createRole(RoleSaveDTO dto) {
        if (roleMapper.selectCount(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, dto.getRoleCode())) > 0) {
            throw new BusinessException("角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setRoleCode(dto.getRoleCode());
        role.setRoleName(dto.getRoleName());
        role.setRoleDesc(dto.getRoleDesc());
        role.setCreateTime(LocalDateTime.now());
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.insert(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(Long id, RoleSaveDTO dto) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        Long count = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, dto.getRoleCode()).ne(SysRole::getId, id));
        if (count > 0) {
            throw new BusinessException("角色编码已存在");
        }
        role.setRoleCode(dto.getRoleCode());
        role.setRoleName(dto.getRoleName());
        role.setRoleDesc(dto.getRoleDesc());
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(id);
        roleMapper.deleteById(id);
        roleMenuMapper.deleteByRoleId(id);
        userRoleMapper.deleteByRoleId(id);
        evictUsers(userIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, AssignMenuDTO dto) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        roleMenuMapper.deleteByRoleId(roleId);
        if (dto.getMenuIds() != null) {
            for (Long menuId : dto.getMenuIds()) {
                roleMenuMapper.insertRelation(roleId, menuId);
            }
        }
        evictUsers(userRoleMapper.selectUserIdsByRoleId(roleId));
    }

    @Override
    public List<Long> getRoleMenuIds(Long roleId) {
        return roleMenuMapper.listByRoleId(roleId).stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toList());
    }

    /**
     * 失效指定用户的权限快照（菜单权限变更立即生效）
     *
     * @param userIds 用户 ID 集合
     */
    private void evictUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Long userId : userIds) {
            try {
                authClient.evict(userId);
            } catch (Exception e) {
                log.warn("失效用户权限快照失败: userId={}, {}", userId, e.getMessage());
            }
        }
    }

    private RoleVO toVO(SysRole role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setRoleDesc(role.getRoleDesc());
        vo.setCreateTime(role.getCreateTime());
        return vo;
    }
}
