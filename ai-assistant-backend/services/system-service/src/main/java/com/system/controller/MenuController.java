package com.system.controller;

import com.api.dto.MenuNodeDTO;
import com.common.result.Result;
import com.common.security.operlog.OperLog;
import com.system.dto.MenuSaveDTO;
import com.system.service.MenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单权限接口
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** 当前登录用户可访问的菜单树（任意登录用户可用，用于前端动态路由） */
    @GetMapping("/tree")
    public Result<List<MenuNodeDTO>> tree() {
        return Result.success(menuService.currentUserMenuTree());
    }

    /** 全部菜单树（角色分配权限时使用） */
    @GetMapping("/all")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public Result<List<MenuNodeDTO>> all() {
        return Result.success(menuService.allMenuTree());
    }

    /** 新增菜单 */
    @OperLog(module = "菜单管理", action = "新增菜单")
    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:add')")
    public Result<Void> add(@Valid @RequestBody MenuSaveDTO dto) {
        menuService.createMenu(dto);
        return Result.success();
    }

    /** 编辑菜单 */
    @OperLog(module = "菜单管理", action = "修改菜单")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:edit')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MenuSaveDTO dto) {
        menuService.updateMenu(id, dto);
        return Result.success();
    }

    /** 删除菜单（含子孙节点） */
    @OperLog(module = "菜单管理", action = "删除菜单")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.deleteMenu(id);
        return Result.success();
    }
}
