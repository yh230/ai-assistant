package com.api.client;

import com.api.dto.*;
import com.common.result.PageResult;
import com.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户中心（system-service）内部接口客户端
 *
 * @author yh
 * @date 2026-10-12
 */
@FeignClient(name = "system-service", path = "/inner/user", contextId = "userClient")
public interface UserClient {

    /** 登录凭据校验并返回权限快照数据（BCrypt 校验在 system 侧完成） */
    @PostMapping("/authenticate")
    Result<AuthInfoDTO> authenticate(@RequestBody AuthRequest req);

    /** 按用户 ID 组装权限快照数据 */
    @GetMapping("/{userId}/auth-info")
    Result<AuthInfoDTO> authInfo(@PathVariable("userId") Long userId);

    /** 按 ID 批量查询用户简要信息 */
    @GetMapping("/batch")
    Result<List<UserBriefDTO>> batch(@RequestParam("ids") List<Long> ids);

    /** 员工分页（hospital 员工管理页调用，过滤参数由调用方按数据权限传入） */
    @PostMapping("/page")
    Result<PageResult<UserBriefDTO>> page(@RequestBody UserPageRequest req);

    /** 按科室/医院查询医生列表 */
    @GetMapping("/doctors")
    Result<List<UserBriefDTO>> doctors(@RequestParam(value = "hospitalId", required = false) Long hospitalId,
                                       @RequestParam(value = "departmentId", required = false) Long departmentId);

    /** 创建员工账号（含角色与医院/科室绑定） */
    @PostMapping
    Result<Long> create(@RequestBody UserCreateRequest req);

    /** 更新员工（或普通用户）信息 */
    @PutMapping("/{id}")
    Result<Void> update(@PathVariable("id") Long id, @RequestBody UserUpdateRequest req);

    /** 删除用户 */
    @DeleteMapping("/{id}")
    Result<Void> delete(@PathVariable("id") Long id);

    /** 更新联系方式（auth 服务 profile 转发） */
    @PutMapping("/{id}/profile")
    Result<Void> updateProfile(@PathVariable("id") Long id, @RequestBody ProfileUpdateDTO req);

    /** 修改密码（auth 服务转发，旧密码校验与 BCrypt 在 system 完成） */
    @PutMapping("/{id}/password")
    Result<Void> changePassword(@PathVariable("id") Long id, @RequestBody ChangePasswordDTO req);

    /** 角色下拉（hospital 员工表单，无需 system:role:list 权限） */
    @GetMapping("/roles")
    Result<List<RoleBriefDTO>> roles();
}
