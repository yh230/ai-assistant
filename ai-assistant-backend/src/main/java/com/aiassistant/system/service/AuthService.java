package com.aiassistant.system.service;

import com.aiassistant.system.dto.ChangePasswordDTO;
import com.aiassistant.system.dto.LoginRequest;
import com.aiassistant.system.dto.ProfileUpdateDTO;
import com.aiassistant.system.vo.LoginVO;
import com.aiassistant.system.vo.UserInfoVO;

/**
 * 认证服务：登录、登出、获取当前用户信息
 * <p>
 * 变更点：登出不再需要 HttpServletRequest（直接撤销 SecurityContext 中的当前令牌）
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
     * 登出：撤销当前令牌（jti 入 Redis 黑名单）
     */
    void logout();

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
