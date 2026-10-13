package com.system.controller.inner;

import com.api.dto.*;
import com.common.result.PageResult;
import com.common.result.Result;
import com.system.service.MenuService;
import com.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户中心内部接口（仅供服务间 Feign 调用，网关阻断外部访问）
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/inner/user")
@RequiredArgsConstructor
public class InnerUserController {

    private final UserService userService;
    private final MenuService menuService;

    /** 登录凭据校验并返回权限快照数据 */
    @PostMapping("/authenticate")
    public Result<AuthInfoDTO> authenticate(@RequestBody AuthRequest req) {
        return Result.success(userService.authenticate(req));
    }

    /** 按用户 ID 组装权限快照数据 */
    @GetMapping("/{userId}/auth-info")
    public Result<AuthInfoDTO> authInfo(@PathVariable("userId") Long userId) {
        return Result.success(userService.buildAuthInfo(userId));
    }

    /** 按 ID 批量查询用户简要信息 */
    @GetMapping("/batch")
    public Result<List<UserBriefDTO>> batch(@RequestParam("ids") List<Long> ids) {
        return Result.success(userService.batchBrief(ids));
    }

    /** 员工分页（过滤参数由调用方按数据权限传入） */
    @PostMapping("/page")
    public Result<PageResult<UserBriefDTO>> page(@RequestBody UserPageRequest req) {
        return Result.success(userService.pageBrief(req));
    }

    /** 按科室/医院查询医生列表 */
    @GetMapping("/doctors")
    public Result<List<UserBriefDTO>> doctors(@RequestParam(value = "hospitalId", required = false) Long hospitalId,
                                              @RequestParam(value = "departmentId", required = false) Long departmentId) {
        return Result.success(userService.listDoctors(hospitalId, departmentId));
    }

    /** 创建员工账号（含角色与医院/科室绑定） */
    @PostMapping
    public Result<Long> create(@RequestBody UserCreateRequest req) {
        return Result.success(userService.createUser(req));
    }

    /** 更新员工/用户信息 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") Long id, @RequestBody UserUpdateRequest req) {
        userService.updateUser(id, req);
        return Result.success();
    }

    /** 删除用户 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        userService.deleteUser(id);
        return Result.success();
    }

    /** 更新当前用户联系方式（auth 服务 profile 转发） */
    @PutMapping("/{id}/profile")
    public Result<Void> updateProfile(@PathVariable("id") Long id, @RequestBody ProfileUpdateDTO req) {
        userService.updateProfile(id, req);
        return Result.success();
    }

    /** 修改密码（auth 服务转发，旧密码校验与加密在 system 完成） */
    @PutMapping("/{id}/password")
    public Result<Void> changePassword(@PathVariable("id") Long id, @RequestBody ChangePasswordDTO req) {
        userService.changePassword(id, req);
        return Result.success();
    }
}
