package com.system.service;

import com.api.dto.ChangePasswordDTO;
import com.api.dto.ProfileUpdateDTO;
import com.system.dto.LoginRequest;
import com.system.vo.LoginVO;
import com.system.vo.UserInfoVO;

/**
 * 认证服务：登录、登出、获取当前用户信息
 * <p>
 * 登出撤销当前令牌，无需请求参数；令牌撤销逻辑随 auth 服务割接落地
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
     * 登出：撤销当前令牌
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
