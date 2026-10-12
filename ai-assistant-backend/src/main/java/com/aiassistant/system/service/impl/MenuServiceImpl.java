package com.aiassistant.system.service.impl;

import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.security.SecurityUtils;
import com.aiassistant.system.dto.MenuSaveDTO;
import com.aiassistant.system.entity.SysMenu;
import com.aiassistant.system.mapper.SysMenuMapper;
import com.aiassistant.system.service.MenuService;
import com.aiassistant.system.vo.MenuTreeVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单权限服务实现：菜单树构建
 * <p>
 * 当前登录人经 {@link SecurityUtils} 从 SecurityContext 获取
 *
 * @author yh
 * @date 2026-10-12
 */
@Service
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private final SysMenuMapper menuMapper;

    /**
     * 当前登录用户的菜单树
     *
     * @return 菜单树；未登录返回空列表
     */
    @Override
    public List<MenuTreeVO> currentUserMenuTree() {
        // 未登录（无有效认证）时返回空列表
        if (!SecurityUtils.isAuthenticated()) {
            return List.of();
        }
        return buildUserMenuTree(SecurityUtils.getCurrentUserId());
    }

    @Override
    public List<MenuTreeVO> allMenuTree() {
        List<SysMenu> menus = menuMapper.selectList(
                new LambdaQueryWrapper<SysMenu>().orderByAsc(SysMenu::getSort));
        return buildTree(menus);
    }

    @Override
    public List<MenuTreeVO> buildUserMenuTree(Long userId) {
        List<SysMenu> menus = menuMapper.selectByUserId(userId).stream()
                .filter(m -> m.getType() != null && m.getType() == 1)
                .toList();
        return buildTree(menus);
    }

    private List<MenuTreeVO> buildTree(List<SysMenu> menus) {
        Map<Long, MenuTreeVO> nodeMap = new LinkedHashMap<>();
        List<MenuTreeVO> roots = new ArrayList<>();
        for (SysMenu m : menus) {
            nodeMap.put(m.getId(), toTreeVO(m));
        }
        for (SysMenu m : menus) {
            MenuTreeVO node = nodeMap.get(m.getId());
            Long parentId = m.getParentId() == null ? 0L : m.getParentId();
            if (parentId == 0L) {
                roots.add(node);
            } else {
                MenuTreeVO parent = nodeMap.get(parentId);
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

    private void sortRecursive(List<MenuTreeVO> nodes) {
        nodes.sort((a, b) -> {
            int sa = a.getSort() == null ? 0 : a.getSort();
            int sb = b.getSort() == null ? 0 : b.getSort();
            return Integer.compare(sa, sb);
        });
        for (MenuTreeVO node : nodes) {
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

    private MenuTreeVO toTreeVO(SysMenu m) {
        MenuTreeVO vo = new MenuTreeVO();
        vo.setId(m.getId());
        vo.setParentId(m.getParentId());
        vo.setMenuName(m.getMenuName());
        vo.setPath(m.getPath());
        vo.setComponent(m.getComponent());
        vo.setPerms(m.getPerms());
        vo.setType(m.getType());
        vo.setSort(m.getSort());
        return vo;
    }
}
