package com.aiassistant.system.service.impl;

import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.common.result.PageResult;
import com.aiassistant.system.dto.AssignMenuDTO;
import com.aiassistant.system.dto.RoleSaveDTO;
import com.aiassistant.system.entity.SysRole;
import com.aiassistant.system.entity.SysRoleMenu;
import com.aiassistant.system.mapper.SysRoleMapper;
import com.aiassistant.system.mapper.SysRoleMenuMapper;
import com.aiassistant.system.mapper.SysUserRoleMapper;
import com.aiassistant.system.service.RoleService;
import com.aiassistant.system.vo.RoleVO;
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
        roleMapper.deleteById(id);
        roleMenuMapper.deleteByRoleId(id);
        userRoleMapper.deleteByRoleId(id);
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
    }

    @Override
    public List<Long> getRoleMenuIds(Long roleId) {
        return roleMenuMapper.listByRoleId(roleId).stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toList());
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
