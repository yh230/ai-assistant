package com.aiassistant.system.controller;

import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.result.Result;
import com.aiassistant.system.dto.AssignMenuDTO;
import com.aiassistant.system.dto.RoleSaveDTO;
import com.aiassistant.system.service.RoleService;
import com.aiassistant.system.vo.RoleVO;

import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色管理接口
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/system/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:role:list')")
    public Result<PageResult<RoleVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword) {
        return Result.success(roleService.pageRoles(keyword, pageNum, pageSize));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:role:add')")
    public Result<Void> add(@Valid @RequestBody RoleSaveDTO dto) {
        roleService.createRole(dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:edit')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleSaveDTO dto) {
        roleService.updateRole(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.deleteRole(id);
        return Result.success();
    }

    @PostMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('system:role:assign')")
    public Result<Void> assignMenus(@PathVariable Long id, @Valid @RequestBody AssignMenuDTO dto) {
        roleService.assignMenus(id, dto);
        return Result.success();
    }

    /** 查询角色已分配的菜单ID集合（前端回显勾选用） */
    @GetMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('system:role:assign')")
    public Result<List<Long>> roleMenus(@PathVariable Long id) {
        return Result.success(roleService.getRoleMenuIds(id));
    }
}
