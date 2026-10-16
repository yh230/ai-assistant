package com.auth.controller;

import com.api.dto.ChangePasswordDTO;
import com.api.dto.ProfileUpdateDTO;
import com.api.dto.SmsLoginRequest;
import com.auth.dto.LoginRequest;
import com.auth.dto.RefreshTokenRequest;
import com.auth.dto.SmsCodeRequest;
import com.auth.service.AuthService;
import com.auth.vo.LoginVO;
import com.auth.vo.UserInfoVO;
import com.common.result.Result;
import com.common.security.operlog.OperLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录、登出、令牌刷新、获取当前用户信息
 * <p>
 * 登出撤销当前令牌并可携带 refresh token 一并撤销
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

    /**
     * 患者登录（手机号+密码，网关白名单免登录）
     *
     * @param request 登录入参（username=手机号）
     * @return 登录结果（JWT 令牌）
     */
    @PostMapping("/patient/login")
    public Result<LoginVO> patientLogin(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.patientLogin(request));
    }

    /**
     * 发送手机号验证码（网关白名单免登录）
     *
     * @param request 发码入参（身份/手机号）
     * @return 空结果
     */
    @PostMapping("/login/sms/code")
    public Result<Void> sendSmsCode(@Valid @RequestBody SmsCodeRequest request) {
        authService.sendSmsCode(request.getIdentity(), request.getPhone());
        return Result.success();
    }

    /**
     * 手机号验证码登录（网关白名单免登录）
     *
     * @param request 登录入参（身份/手机号/验证码）
     * @return 登录结果（令牌对）
     */
    @PostMapping("/login/sms")
    public Result<LoginVO> smsLogin(@Valid @RequestBody SmsLoginRequest request) {
        return Result.success(authService.smsLogin(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request,
                               @RequestBody(required = false) RefreshTokenRequest body) {
        authService.logout(request, body == null ? null : body.getRefreshToken());
        return Result.success();
    }

    /**
     * 刷新令牌（旧 refresh token 轮换失效，返回新 access + 新 refresh token）
     *
     * @param request 刷新入参
     * @return 新令牌对
     */
    @PostMapping("/refresh")
    public Result<LoginVO> refresh(@RequestBody RefreshTokenRequest request) {
        return Result.success(authService.refresh(request.getRefreshToken()));
    }

    /**
     * 撤销刷新令牌（登出兜底，幂等）
     *
     * @param request 刷新令牌入参
     * @return 空结果
     */
    @PostMapping("/refresh/revoke")
    public Result<Void> revokeRefresh(@RequestBody RefreshTokenRequest request) {
        authService.revokeRefresh(request.getRefreshToken());
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

    @OperLog(module = "个人中心", action = "修改资料")
    @PostMapping("/profile")
    public Result<Void> updateProfile(@RequestBody ProfileUpdateDTO dto) {
        authService.updateProfile(dto);
        return Result.success();
    }

    @OperLog(module = "个人中心", action = "修改密码")
    @PostMapping("/profile/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        authService.changePassword(dto);
        return Result.success();
    }
}
