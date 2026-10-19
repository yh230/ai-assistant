package com.aiassistant.controller;

import com.common.auth.UserSnapshot;
import com.common.result.Result;
import com.common.security.SecurityUtils;
import com.common.security.operlog.OperLog;
import com.aiassistant.dto.RoleForm;
import com.aiassistant.entity.Role;
import com.aiassistant.service.AssistantRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理接口（供「角色管理」页面使用，管理 AI 助手的人设/角色）
 * <p>
 * 与聊天选择器接口 {@code GET /api/chat/roles}（仅返回已启用角色）不同，
 * 本组接口面向后台维护，提供按角色域/按医院的列表与增删改查，统一要求 {@code ai:role:manage} 权限。
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
     * 角色列表（含禁用），按角色域过滤；患者域必须指定医院
     * GET /api/roles/admin?scope=&hospitalId=
     *
     * @param scope 角色域 STAFF/PATIENT，缺省 STAFF
     * @param hospitalId 医院 ID（患者域必填；登录人已绑院时以登录人为准）
     * @return 角色列表
     */
    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('ai:role:manage')")
    public Result<List<Role>> listAll(@RequestParam(defaultValue = "STAFF") String scope,
                                      @RequestParam(required = false) Long hospitalId) {
        return Result.success(assistantRoleService.listByScope(scope, resolveHospital(hospitalId)));
    }

    /**
     * 获取单个角色详情
     * GET /api/roles/admin/{id}
     *
     * @param id 角色标识
     * @return 角色详情，不存在时返回 404 失败结果
     */
    @GetMapping("/admin/{id}")
    @PreAuthorize("hasAuthority('ai:role:manage')")
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
    @PreAuthorize("hasAuthority('ai:role:manage')")
    public Result<Role> create(@RequestBody RoleForm form) {
        return Result.success(assistantRoleService.createForScope(form, resolveHospital(form.getHospitalId())));
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
    @PreAuthorize("hasAuthority('ai:role:manage')")
    public Result<Role> update(@PathVariable String id, @RequestBody RoleForm form) {
        return Result.success(assistantRoleService.updateForScope(id, form, resolveHospital(form.getHospitalId())));
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
    @PreAuthorize("hasAuthority('ai:role:manage')")
    public Result<Void> delete(@PathVariable String id) {
        assistantRoleService.delete(id);
        return Result.success();
    }

    /**
     * 解析操作医院：登录人已绑院时强制用本人医院，未绑院时使用显式入参
     *
     * @param explicitHospitalId 显式传入的医院 ID
     * @return 实际生效的医院 ID（无绑院且未显式传入时为 null）
     */
    private Long resolveHospital(Long explicitHospitalId) {
        UserSnapshot snapshot = SecurityUtils.currentSnapshot();
        Long bound = snapshot == null ? null : snapshot.getHospitalId();
        return bound != null ? bound : explicitHospitalId;
    }
}
