package com.system.controller;

import com.api.dto.ResetPasswordDTO;
import com.api.dto.UserCreateRequest;
import com.api.dto.UserUpdateRequest;
import com.common.result.PageResult;
import com.common.result.Result;
import com.system.service.UserService;
import com.system.vo.UserVO;
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
 * 员工管理接口
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/system/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:user:list')")
    public Result<PageResult<UserVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword) {
        return Result.success(userService.pageUsers(keyword, pageNum, pageSize));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:user:add')")
    public Result<Void> add(@Valid @RequestBody UserCreateRequest dto) {
        userService.createUser(dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:edit')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest dto) {
        userService.updateUser(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:delete')")
    public Result<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.success();
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('system:user:edit')")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        userService.changeStatus(id, status);
        return Result.success();
    }

    /**
     * 重置指定用户密码
     * <p>
     * 请求体为空时按「使用默认密码重置」处理（对齐医院员工端防 NPE 写法）
     *
     * @param id 用户 ID
     * @param dto 重置密码请求体（可空）
     * @return 操作结果
     */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('system:user:reset')")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @RequestBody(required = false) ResetPasswordDTO dto) {
        userService.resetPassword(id, dto == null ? new ResetPasswordDTO() : dto);
        return Result.success();
    }

    /**
     * 强制指定用户下线
     *
     * @param id 用户 ID
     * @return 操作结果
     */
    @PostMapping("/{id}/kickout")
    @PreAuthorize("hasRole('admin')") // 对应 RoleConstants.ADMIN；SecurityConfig 已配置 rolePrefix=""
    public Result<Void> kickout(@PathVariable Long id) {
        userService.kickoutUser(id);
        return Result.success();
    }
}
