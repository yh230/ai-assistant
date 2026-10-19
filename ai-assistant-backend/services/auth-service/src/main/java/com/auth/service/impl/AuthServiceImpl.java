package com.auth.service.impl;

import com.api.client.PatientClient;
import com.api.client.UserClient;
import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.ChangePasswordDTO;
import com.api.dto.ProfileUpdateDTO;
import com.api.dto.SmsLoginRequest;
import com.auth.dto.LoginRequest;
import com.auth.login.LoginIssuer;
import com.auth.service.AuthService;
import com.auth.sms.SmsCodeService;
import com.auth.snapshot.SnapshotManager;
import com.auth.support.LoginAttemptGuard;
import com.auth.vo.LoginVO;
import com.auth.vo.UserInfoVO;
import com.common.auth.UserSnapshot;
import com.common.auth.UserSnapshotStore;
import com.common.constant.SecurityConstants;
import com.common.exception.BusinessException;
import com.common.jwt.JwtTokenService;
import com.common.jwt.RefreshTokenStore;
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
 * 员工登录凭据校验经 {@link UserClient#authenticate} 下沉用户中心、患者登录经 {@link PatientClient#authenticate}
 * 下沉患者中心，通过后签发 JWT 与刷新令牌并写权限快照；
 * 刷新令牌走轮换（消费旧 refresh token 签发新令牌对）；登出按 jti 撤销当前令牌并可一并撤销刷新令牌；
 * getInfo 优先权限快照，菜单树经用户中心实时组装
 *
 * @author yh
 * @date 2026-10-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserClient userClient;
    private final PatientClient patientClient;
    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final UserSnapshotStore userSnapshotStore;
    private final SnapshotManager snapshotManager;
    private final LoginAttemptGuard loginAttemptGuard;
    private final RefreshTokenStore refreshTokenStore;
    private final SmsCodeService smsCodeService;
    private final LoginIssuer loginIssuer;

    /**
     * 登录认证（账号密码校验经 Feign 下沉用户中心，通过后签发 JWT 与刷新令牌并写快照）
     *
     * @param request 登录入参（账号/密码）
     * @return 登录结果（JWT 令牌与刷新令牌）
     */
    @Override
    public LoginVO login(LoginRequest request) {
        loginAttemptGuard.assertNotLocked(SecurityConstants.IDENTITY_STAFF, request.getUsername());
        // 账号不存在/密码错误/账号禁用时由用户中心返回失败码，文案逐字透传
        AuthRequest authRequest = new AuthRequest();
        authRequest.setUsername(request.getUsername());
        authRequest.setPassword(request.getPassword());
        Result<AuthInfoDTO> result = userClient.authenticate(authRequest);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            loginAttemptGuard.recordFailure(SecurityConstants.IDENTITY_STAFF, request.getUsername());
            throw new BusinessException(result == null ? "登录失败" : result.getMsg());
        }
        AuthInfoDTO info = result.getData();
        loginAttemptGuard.reset(SecurityConstants.IDENTITY_STAFF, request.getUsername());
        String token = jwtTokenService.issue(info.getUser().getId());
        snapshotManager.saveFromLogin(info.getUser().getId(), info);
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setRefreshToken(refreshTokenStore.issue(SecurityConstants.IDENTITY_STAFF, info.getUser().getId()));
        return vo;
    }

    /**
     * 患者登录认证（凭据校验经 Feign 下沉患者中心，通过后签发 PATIENT 身份 JWT 与刷新令牌并写患者快照）
     *
     * @param request 登录入参（username=手机号）
     * @return 登录结果（JWT 令牌与刷新令牌）
     */
    @Override
    public LoginVO patientLogin(LoginRequest request) {
        loginAttemptGuard.assertNotLocked(SecurityConstants.IDENTITY_PATIENT, request.getUsername());
        // 手机号不存在/密码错误/账号禁用时由患者中心返回失败码，文案逐字透传
        AuthRequest authRequest = new AuthRequest();
        authRequest.setUsername(request.getUsername());
        authRequest.setPassword(request.getPassword());
        Result<AuthInfoDTO> result = patientClient.authenticate(authRequest);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            loginAttemptGuard.recordFailure(SecurityConstants.IDENTITY_PATIENT, request.getUsername());
            throw new BusinessException(result == null ? "登录失败" : result.getMsg());
        }
        AuthInfoDTO info = result.getData();
        loginAttemptGuard.reset(SecurityConstants.IDENTITY_PATIENT, request.getUsername());
        String token = jwtTokenService.issue(info.getUser().getId(), SecurityConstants.IDENTITY_PATIENT);
        snapshotManager.saveFromPatientLogin(info.getUser().getId(), info);
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setRefreshToken(refreshTokenStore.issue(SecurityConstants.IDENTITY_PATIENT, info.getUser().getId()));
        return vo;
    }

    /**
     * 发送手机号验证码（校验身份后按节流规则下发）
     *
     * @param identity 身份（staff/patient）
     * @param phone 手机号
     */
    @Override
    public void sendSmsCode(String identity, String phone) {
        validateIdentity(identity);
        smsCodeService.sendCode(identity, phone);
    }

    /**
     * 手机号验证码登录（校验验证码 → Feign 按手机号解析账号 → 签发令牌并写快照）
     * <p>
     * 校验失败或手机号未绑定账号均计入 {@link LoginAttemptGuard}
     *
     * @param request 登录入参（身份/手机号/验证码）
     * @return 登录结果（令牌对）
     */
    @Override
    public LoginVO smsLogin(SmsLoginRequest request) {
        String identity = request.getIdentity();
        validateIdentity(identity);
        String phone = request.getPhone();
        loginAttemptGuard.assertNotLocked(identity, phone);
        try {
            smsCodeService.verify(identity, phone, request.getCode());
        } catch (BusinessException e) {
            loginAttemptGuard.recordFailure(identity, phone);
            throw e;
        }
        Result<AuthInfoDTO> result = SecurityConstants.IDENTITY_PATIENT.equals(identity)
                ? patientClient.byPhone(phone) : userClient.byPhone(phone);
        if (result == null || result.getCode() != 200 || result.getData() == null
                || result.getData().getUser() == null) {
            loginAttemptGuard.recordFailure(identity, phone);
            throw new BusinessException("手机号未绑定账号，请联系管理员");
        }
        Long userId = result.getData().getUser().getId();
        loginAttemptGuard.reset(identity, phone);
        return loginIssuer.issue(identity, userId);
    }

    private void validateIdentity(String identity) {
        if (!SecurityConstants.IDENTITY_STAFF.equals(identity)
                && !SecurityConstants.IDENTITY_PATIENT.equals(identity)) {
            throw new BusinessException("非法身份类型");
        }
    }

    /**
     * 登出：先撤销携带的刷新令牌（可空），再解析请求头 Bearer 令牌并撤销（jti 入 Redis 黑名单，TTL=剩余有效期）
     * <p>
     * 令牌解析失败静默返回
     *
     * @param request HTTP 请求（携带 Authorization 头）
     * @param refreshToken 刷新令牌串（可空）
     */
    @Override
    public void logout(HttpServletRequest request, String refreshToken) {
        refreshTokenStore.revoke(refreshToken);
        String header = request.getHeader(SecurityConstants.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            return;
        }
        try {
            String token = header.substring(SecurityConstants.TOKEN_PREFIX.length());
            JwtTokenService.JwtPayload payload = jwtTokenService.parse(token);
            tokenRevocationService.revokeToken(payload.jti(), payload.expiresAtMillis());
        } catch (JwtException | IllegalArgumentException e) {
            // 无效/过期/被篡改令牌：静默返回
            log.debug("登出令牌解析失败: {}", e.getMessage());
        }
    }

    /**
     * 刷新令牌（轮换：消费旧 RT → 校验踢下线 → 签发新 access 与新 RT）
     *
     * @param refreshToken 刷新令牌串
     * @return 新令牌对
     */
    @Override
    public LoginVO refresh(String refreshToken) {
        RefreshTokenStore.RefreshTokenInfo info = refreshTokenStore.consume(refreshToken);
        if (info == null) {
            throw new BusinessException("登录已过期，请重新登录");
        }
        long kickedAt = tokenRevocationService.kickoutAt(info.identity(), info.userId());
        if (kickedAt != 0 && info.issuedAtMillis() <= kickedAt) {
            throw new BusinessException("登录已过期，请重新登录");
        }
        LoginVO vo = new LoginVO();
        vo.setToken(jwtTokenService.issue(info.userId(), info.identity()));
        vo.setRefreshToken(refreshTokenStore.issue(info.identity(), info.userId()));
        return vo;
    }

    /**
     * 撤销刷新令牌（幂等）
     *
     * @param refreshToken 刷新令牌串
     */
    @Override
    public void revokeRefresh(String refreshToken) {
        refreshTokenStore.revoke(refreshToken);
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
        UserSnapshot snapshot = userSnapshotStore.get(SecurityConstants.IDENTITY_STAFF, userId);
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
        vo.setHospitalId(snapshot.getHospitalId());
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
