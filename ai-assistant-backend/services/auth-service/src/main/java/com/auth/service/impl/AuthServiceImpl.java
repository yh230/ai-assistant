package com.auth.service.impl;

import com.api.client.UserClient;
import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.ChangePasswordDTO;
import com.api.dto.ProfileUpdateDTO;
import com.auth.dto.LoginRequest;
import com.auth.service.AuthService;
import com.auth.snapshot.SnapshotManager;
import com.auth.vo.LoginVO;
import com.auth.vo.UserInfoVO;
import com.common.auth.UserSnapshot;
import com.common.auth.UserSnapshotStore;
import com.common.constant.SecurityConstants;
import com.common.exception.BusinessException;
import com.common.jwt.JwtTokenService;
import com.common.jwt.TokenRevocationService;
import com.common.result.Result;
import com.common.security.SecurityUtils;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证服务实现（认证中心）
 * <p>
 * 登录凭据校验经 {@link UserClient#authenticate} 下沉用户中心，通过后签发 JWT 并写权限快照；
 * 登出按 jti 撤销当前令牌；getInfo 优先权限快照，菜单树经用户中心实时组装
 *
 * @author yh
 * @date 2026-10-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserClient userClient;
    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final UserSnapshotStore userSnapshotStore;
    private final SnapshotManager snapshotManager;

    /**
     * 登录认证（账号密码校验经 Feign 下沉用户中心，通过后签发 JWT 并写快照）
     *
     * @param request 登录入参（账号/密码）
     * @return 登录结果（JWT 令牌）
     */
    @Override
    public LoginVO login(LoginRequest request) {
        // 账号不存在/密码错误/账号禁用时由用户中心返回失败码，文案逐字透传
        AuthRequest authRequest = new AuthRequest();
        authRequest.setUsername(request.getUsername());
        authRequest.setPassword(request.getPassword());
        Result<AuthInfoDTO> result = userClient.authenticate(authRequest);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new BusinessException(result == null ? "登录失败" : result.getMsg());
        }
        AuthInfoDTO info = result.getData();
        String token = jwtTokenService.issue(info.getUser().getId());
        snapshotManager.saveFromLogin(info.getUser().getId(), info);
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        return vo;
    }

    /**
     * 登出：解析请求头 Bearer 令牌并撤销（jti 入 Redis 黑名单，TTL=剩余有效期）
     * <p>
     * 解析失败静默返回，语义同旧单体
     *
     * @param request HTTP 请求（携带 Authorization 头）
     */
    @Override
    public void logout(HttpServletRequest request) {
        String header = request.getHeader(SecurityConstants.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            return;
        }
        try {
            String token = header.substring(SecurityConstants.TOKEN_PREFIX.length());
            JwtTokenService.JwtPayload payload = jwtTokenService.parse(token);
            tokenRevocationService.revokeToken(payload.jti(), payload.expiresAtMillis());
        } catch (JwtException | IllegalArgumentException e) {
            // 无效/过期/被篡改令牌：静默返回，语义同旧单体
            log.debug("登出令牌解析失败: {}", e.getMessage());
        }
    }

    /**
     * 当前登录人信息（含角色码/权限标识/菜单树）
     * <p>
     * 角色/权限取权限快照（缺失时经用户中心重建）；菜单树与手机号/邮箱/状态快照不含，
     * 经用户中心实时补齐
     *
     * @return 用户信息视图
     */
    @Override
    public UserInfoVO getInfo() {
        Long userId = SecurityUtils.getCurrentUserId();
        UserSnapshot snapshot = userSnapshotStore.get(userId);
        if (snapshot == null) {
            snapshot = snapshotManager.refresh(userId);
        }
        if (snapshot == null) {
            throw new BusinessException("用户不存在");
        }
        Result<AuthInfoDTO> result = userClient.authInfo(userId);
        AuthInfoDTO info = (result != null && result.getCode() == 200) ? result.getData() : null;

        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(userId);
        vo.setUsername(snapshot.getUsername());
        vo.setName(snapshot.getName());
        if (info != null && info.getUser() != null) {
            vo.setPhone(info.getUser().getPhone());
            vo.setEmail(info.getUser().getEmail());
            vo.setStatus(info.getUser().getStatus());
        }
        vo.setRoles(snapshot.getRoles());
        vo.setPermissions(snapshot.getPerms());
        vo.setMenus(info != null ? info.getMenus() : null);
        return vo;
    }

    @Override
    public UserInfoVO getProfile() {
        // 个人信息即当前登录用户资料，复用 getInfo（已含 email）
        return getInfo();
    }

    /**
     * 更新当前登录人资料（仅手机号/邮箱），经 Feign 转发用户中心
     *
     * @param dto 资料更新入参
     */
    @Override
    public void updateProfile(ProfileUpdateDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();
        Result<Void> result = userClient.updateProfile(userId, dto);
        if (result == null || result.getCode() != 200) {
            throw new BusinessException(result == null ? "修改资料失败" : result.getMsg());
        }
    }

    /**
     * 修改当前登录人密码（旧密码校验与 BCrypt 在用户中心完成）
     *
     * @param dto 改密入参（原密码/新密码）
     */
    @Override
    public void changePassword(ChangePasswordDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();
        Result<Void> result = userClient.changePassword(userId, dto);
        if (result == null || result.getCode() != 200) {
            throw new BusinessException(result == null ? "修改密码失败" : result.getMsg());
        }
    }
}
