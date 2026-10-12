package com.aiassistant.system.controller;

import com.aiassistant.common.result.Result;
import com.aiassistant.system.dto.ChangePasswordDTO;
import com.aiassistant.system.dto.LoginRequest;
import com.aiassistant.system.dto.ProfileUpdateDTO;
import com.aiassistant.system.service.AuthService;
import com.aiassistant.system.vo.LoginVO;
import com.aiassistant.system.vo.UserInfoVO;
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
 * 登出撤销 SecurityContext 中的当前令牌（jti 由认证过滤器放入），无需从请求解析 token
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
