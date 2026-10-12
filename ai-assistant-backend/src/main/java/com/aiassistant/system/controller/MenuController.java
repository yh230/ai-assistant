package com.aiassistant.system.controller;

import com.aiassistant.common.result.Result;
import com.aiassistant.system.dto.MenuSaveDTO;
import com.aiassistant.system.service.MenuService;
import com.aiassistant.system.vo.MenuTreeVO;
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
 * <p>
 * 变更点（Sa-Token→Spring Security 迁移）：权限注解由 {@code @SaCheckPermission("x")} 替换为
 * {@code @PreAuthorize("hasAuthority('x')")}，校验逻辑由 Spring Security 方法级鉴权承担；
 * /tree 仍只需登录（由 SecurityConfig 过滤器链的 authenticated 规则承担）。
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
    public Result<List<MenuTreeVO>> tree() {
        return Result.success(menuService.currentUserMenuTree());
    }

    /** 全部菜单树（角色分配权限时使用） */
    @GetMapping("/all")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public Result<List<MenuTreeVO>> all() {
        return Result.success(menuService.allMenuTree());
    }

    /** 新增菜单 */
    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:add')")
    public Result<Void> add(@Valid @RequestBody MenuSaveDTO dto) {
        menuService.createMenu(dto);
        return Result.success();
    }

    /** 编辑菜单 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:edit')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MenuSaveDTO dto) {
        menuService.updateMenu(id, dto);
        return Result.success();
    }

    /** 删除菜单（含子孙节点） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.deleteMenu(id);
        return Result.success();
    }
}
