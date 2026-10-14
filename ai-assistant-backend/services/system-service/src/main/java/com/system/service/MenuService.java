package com.system.service;

import com.api.dto.MenuNodeDTO;
import com.system.dto.MenuSaveDTO;

import java.util.List;

/**
 * 菜单权限服务。
 */
public interface MenuService {

    /** 当前登录用户可访问菜单树 */
    List<MenuNodeDTO> currentUserMenuTree();

    /** 全部菜单树（角色分配时使用，含按钮） */
    List<MenuNodeDTO> allMenuTree();

    /** 构建指定用户的菜单树（仅菜单类型） */
    List<MenuNodeDTO> buildUserMenuTree(Long userId);

    /** 新增菜单 */
    void createMenu(MenuSaveDTO dto);

    /** 编辑菜单 */
    void updateMenu(Long id, MenuSaveDTO dto);

    /** 删除菜单（同时删除其全部子孙节点，避免产生孤儿节点） */
    void deleteMenu(Long id);
}
