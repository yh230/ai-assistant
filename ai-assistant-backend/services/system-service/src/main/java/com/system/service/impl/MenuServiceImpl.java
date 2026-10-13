package com.system.service.impl;

import com.api.client.AuthClient;
import com.api.dto.MenuNodeDTO;
import com.common.constant.SecurityConstants;
import com.common.exception.BusinessException;
import com.common.security.SecurityUtils;
import com.system.dto.MenuSaveDTO;
import com.system.entity.SysMenu;
import com.system.mapper.SysMenuMapper;
import com.system.mapper.SysUserRoleMapper;
import com.system.service.MenuService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜单权限服务实现：菜单树构建
 * <p>
 * 当前登录人经 {@link SecurityUtils} 从 SecurityContext 获取；
 * 菜单权限变更后经 {@link AuthClient} 失效受影响用户的权限快照
 *
 * @author yh
 * @date 2026-10-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private final SysMenuMapper menuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final AuthClient authClient;

    /**
     * 当前登录用户的菜单树
     *
     * @return 菜单树；未登录返回空列表
     */
    @Override
    public List<MenuNodeDTO> currentUserMenuTree() {
        // 未登录（无有效认证）时返回空列表
        if (SecurityUtils.currentSnapshot() == null) {
            return List.of();
        }
        return buildUserMenuTree(SecurityUtils.getCurrentUserId());
    }

    @Override
    public List<MenuNodeDTO> allMenuTree() {
        List<SysMenu> menus = menuMapper.selectList(
                new LambdaQueryWrapper<SysMenu>().orderByAsc(SysMenu::getSort));
        return buildTree(menus);
    }

    @Override
    public List<MenuNodeDTO> buildUserMenuTree(Long userId) {
        List<SysMenu> menus = menuMapper.selectByUserId(userId).stream()
                .filter(m -> m.getType() != null && m.getType() == 1)
                .toList();
        return buildTree(menus);
    }

    private List<MenuNodeDTO> buildTree(List<SysMenu> menus) {
        Map<Long, MenuNodeDTO> nodeMap = new LinkedHashMap<>();
        List<MenuNodeDTO> roots = new ArrayList<>();
        for (SysMenu m : menus) {
            nodeMap.put(m.getId(), toNode(m));
        }
        for (SysMenu m : menus) {
            MenuNodeDTO node = nodeMap.get(m.getId());
            Long parentId = m.getParentId() == null ? 0L : m.getParentId();
            if (parentId == 0L) {
                roots.add(node);
            } else {
                MenuNodeDTO parent = nodeMap.get(parentId);
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        sortRecursive(roots);
        return roots;
    }

    private void sortRecursive(List<MenuNodeDTO> nodes) {
        nodes.sort((a, b) -> {
            int sa = a.getSort() == null ? 0 : a.getSort();
            int sb = b.getSort() == null ? 0 : b.getSort();
            return Integer.compare(sa, sb);
        });
        for (MenuNodeDTO node : nodes) {
            if (node.getChildren() != null) {
                sortRecursive(node.getChildren());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createMenu(MenuSaveDTO dto) {
        SysMenu menu = new SysMenu();
        menu.setParentId(dto.getParentId() == null ? 0L : dto.getParentId());
        menu.setMenuName(dto.getMenuName());
        menu.setPath(dto.getPath());
        menu.setComponent(dto.getComponent());
        menu.setPerms(dto.getPerms());
        menu.setType(dto.getType());
        menu.setSort(dto.getSort());
        menu.setCreateTime(LocalDateTime.now());
        menu.setUpdateTime(LocalDateTime.now());
        menuMapper.insert(menu);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(Long id, MenuSaveDTO dto) {
        SysMenu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException("菜单不存在");
        }
        menu.setParentId(dto.getParentId() == null ? 0L : dto.getParentId());
        menu.setMenuName(dto.getMenuName());
        menu.setPath(dto.getPath());
        menu.setComponent(dto.getComponent());
        menu.setPerms(dto.getPerms());
        menu.setType(dto.getType());
        menu.setSort(dto.getSort());
        menu.setUpdateTime(LocalDateTime.now());
        menuMapper.updateById(menu);
        evictUsersByMenu(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(Long id) {
        SysMenu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException("菜单不存在");
        }
        // 收集自身及全部子孙节点 ID，避免产生孤儿节点
        List<SysMenu> all = menuMapper.selectList(new LambdaQueryWrapper<>());
        List<Long> toDelete = collectDescendantIds(all, id);
        for (Long mid : toDelete) {
            menuMapper.deleteById(mid);
        }
        for (Long mid : toDelete) {
            evictUsersByMenu(mid);
        }
    }

    private List<Long> collectDescendantIds(List<SysMenu> all, Long rootId) {
        List<Long> result = new ArrayList<>();
        result.add(rootId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (SysMenu m : all) {
                if (m.getParentId() != null && result.contains(m.getParentId()) && !result.contains(m.getId())) {
                    result.add(m.getId());
                    changed = true;
                }
            }
        }
        return result;
    }

    /**
     * 失效所有经由角色持有该菜单的用户权限快照（菜单权限变更立即生效）
     *
     * @param menuId 菜单 ID
     */
    private void evictUsersByMenu(Long menuId) {
        List<Long> userIds = userRoleMapper.selectUserIdsByMenuId(menuId);
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = new HashSet<>(userIds);
        for (Long userId : distinct) {
            evictAuth(userId);
        }
    }

    /**
     * 失效指定用户的权限快照（auth 服务不可用不影响本服务主流程）
     *
     * @param userId 用户 ID
     */
    private void evictAuth(Long userId) {
        try {
            authClient.evict(SecurityConstants.IDENTITY_STAFF, userId);
        } catch (Exception e) {
            log.warn("失效用户权限快照失败: userId={}, {}", userId, e.getMessage());
        }
    }

    private MenuNodeDTO toNode(SysMenu m) {
        MenuNodeDTO node = new MenuNodeDTO();
        node.setId(m.getId());
        node.setParentId(m.getParentId());
        node.setMenuName(m.getMenuName());
        node.setPath(m.getPath());
        node.setComponent(m.getComponent());
        node.setPerms(m.getPerms());
        node.setType(m.getType());
        node.setSort(m.getSort());
        return node;
    }
}
