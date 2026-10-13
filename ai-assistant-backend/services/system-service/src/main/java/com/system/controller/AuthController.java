package com.system.controller;

import com.api.dto.ChangePasswordDTO;
import com.api.dto.ProfileUpdateDTO;
import com.common.result.Result;
import com.system.dto.LoginRequest;
import com.system.service.AuthService;
import com.system.vo.LoginVO;
import com.system.vo.UserInfoVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录、登出、获取当前用户信息
 * <p>
 * 登出撤销当前令牌，无需请求参数；令牌撤销逻辑随 auth 服务割接落地
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    @GetMapping("/getInfo")
    public Result<UserInfoVO> getInfo() {
        return Result.success(authService.getInfo());
    }

    @GetMapping("/profile")
    public Result<UserInfoVO> profile() {
        return Result.success(authService.getProfile());
    }

    @PostMapping("/profile")
    public Result<Void> updateProfile(@RequestBody ProfileUpdateDTO dto) {
        authService.updateProfile(dto);
        return Result.success();
    }

    @PostMapping("/profile/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        authService.changePassword(dto);
        return Result.success();
    }
}
