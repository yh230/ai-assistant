package com.aiassistant.controller;

import com.common.result.Result;
import com.common.security.operlog.OperLog;
import com.aiassistant.dto.RoleForm;
import com.aiassistant.entity.Role;
import com.aiassistant.service.AssistantRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理接口（供「角色管理」页面使用，管理 AI 助手的人设/角色）
 * <p>
 * 与聊天选择器接口 {@code GET /api/chat/roles}（仅返回已启用角色）不同，
 * 本组接口面向后台维护，提供全量列表与增删改查。
 * <p>
 * 注：此类由原 {@code RoleController} 重命名而来，以避免与 RBAC 模块中的
 * {@code com.aiassistant.system.controller.RoleController}（权限角色管理）产生同名 Bean 冲突。
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class AssistantRoleController {

    private final AssistantRoleService assistantRoleService;

    /**
     * 全量角色列表（含禁用），供管理页面表格展示
     * GET /api/roles/admin
     *
     * @return 全量角色列表
     */
    @GetMapping("/admin")
    public Result<List<Role>> listAll() {
        return Result.success(assistantRoleService.listAll());
    }

    /**
     * 获取单个角色详情
     * GET /api/roles/admin/{id}
     *
     * @param id 角色标识
     * @return 角色详情，不存在时返回 404 失败结果
     */
    @GetMapping("/admin/{id}")
    public Result<Role> getOne(@PathVariable String id) {
        return assistantRoleService.findById(id)
                .map(Result::success)
                .orElse(Result.fail(404, "角色不存在: " + id));
    }

    /**
     * 新增角色
     * POST /api/roles/admin
     *
     * @param form 角色表单
     * @return 新建的角色
     */
    @OperLog(module = "助手角色", action = "新增助手角色")
    @PostMapping("/admin")
    public Result<Role> create(@RequestBody RoleForm form) {
        return Result.success(assistantRoleService.create(form));
    }

    /**
     * 更新角色
     * PUT /api/roles/admin/{id}
     *
     * @param id 角色标识
     * @param form 角色表单
     * @return 更新后的角色
     */
    @OperLog(module = "助手角色", action = "修改助手角色")
    @PutMapping("/admin/{id}")
    public Result<Role> update(@PathVariable String id, @RequestBody RoleForm form) {
        return Result.success(assistantRoleService.update(id, form));
    }

    /**
     * 删除角色
     * DELETE /api/roles/admin/{id}
     *
     * @param id 角色标识
     * @return 操作结果
     */
    @OperLog(module = "助手角色", action = "删除助手角色")
    @DeleteMapping("/admin/{id}")
    public Result<Void> delete(@PathVariable String id) {
        assistantRoleService.delete(id);
        return Result.success();
    }
}
