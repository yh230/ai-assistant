package com.auth.service;

import com.api.dto.ChangePasswordDTO;
import com.api.dto.ProfileUpdateDTO;
import com.auth.dto.LoginRequest;
import com.auth.vo.LoginVO;
import com.auth.vo.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 认证服务：登录、登出、令牌刷新、获取当前用户信息
 * <p>
 * 登录凭据校验/资料与密码更新经 Feign 调用用户中心；登出撤销当前令牌并可一并撤销刷新令牌
 *
 * @author yh
 * @date 2026-10-12
 */
public interface AuthService {

    /**
     * 账号密码登录，返回 JWT 令牌
     */
    LoginVO login(LoginRequest request);

    /**
     * 患者登录认证（凭据校验经 Feign 下沉患者中心，通过后签发 PATIENT 身份 JWT 并写患者快照）
     *
     * @param request 登录入参（username=手机号）
     * @return 登录结果（JWT 令牌）
     */
    LoginVO patientLogin(LoginRequest request);

    /**
     * 登出：撤销当前令牌，refreshToken 可空，携带则一并撤销
     *
     * @param request HTTP 请求（携带 Authorization 头）
     * @param refreshToken 刷新令牌串（可空）
     */
    void logout(HttpServletRequest request, String refreshToken);

    /**
     * 刷新令牌（轮换：消费旧 refresh token，签发新 access 与新 refresh token）
     *
     * @param refreshToken 刷新令牌串
     * @return 新令牌对
     */
    LoginVO refresh(String refreshToken);

    /**
     * 撤销刷新令牌（幂等）
     *
     * @param refreshToken 刷新令牌串
     */
    void revokeRefresh(String refreshToken);

    /**
     * 获取当前登录用户的信息、角色、按钮权限、菜单树。
     */
    UserInfoVO getInfo();

    /**
     * 获取当前登录用户的个人资料（同 getInfo，含 email）。
     */
    UserInfoVO getProfile();

    /**
     * 修改当前登录用户的手机号、邮箱（非必填）。
     */
    void updateProfile(ProfileUpdateDTO dto);

    /**
     * 修改当前登录用户密码（需校验原密码）。
     */
    void changePassword(ChangePasswordDTO dto);
}
