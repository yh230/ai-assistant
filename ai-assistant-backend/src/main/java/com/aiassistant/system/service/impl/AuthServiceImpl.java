package com.aiassistant.system.service.impl;

import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.security.DbUserDetailsService.LoginUser;
import com.aiassistant.security.JwtTokenService;
import com.aiassistant.security.SecurityUtils;
import com.aiassistant.security.TokenRevocationService;
import com.aiassistant.system.dto.ChangePasswordDTO;
import com.aiassistant.system.dto.LoginRequest;
import com.aiassistant.system.dto.ProfileUpdateDTO;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysMenuMapper;
import com.aiassistant.system.mapper.SysRoleMapper;
import com.aiassistant.system.mapper.SysUserMapper;
import com.aiassistant.system.service.AuthService;
import com.aiassistant.system.service.MenuService;
import com.aiassistant.system.vo.LoginVO;
import com.aiassistant.system.vo.MenuTreeVO;
import com.aiassistant.system.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

import java.time.LocalDateTime;

/**
 * 认证服务实现
 * <p>
 * 变更点（由 Sa-Token 迁移）：
 * <ul>
 *     <li>登录：交由 {@link AuthenticationManager} 校验账号密码，通过后由 {@link JwtTokenService} 签发 JWT</li>
 *     <li>登出：当前令牌 jti 撤入 Redis 黑名单（{@link TokenRevocationService#revokeToken}，TTL=剩余有效期），即时失效</li>
 *     <li>getInfo/updateProfile/changePassword：当前登录人改经 {@link SecurityUtils} 从 SecurityContext 获取</li>
 *     <li>密码校验：仍用 BCryptPasswordEncoder（登录经 DaoAuthenticationProvider、改密本地校验）</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final MenuService menuService;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;

    /**
     * 登录认证（AuthenticationManager 校验账号密码，通过后签发 JWT）
     *
     * @param request 登录入参（账号/密码）
     * @return 登录结果（JWT 令牌）
     */
    @Override
    public LoginVO login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        } catch (DisabledException e) {
            // 账号禁用：文案与迁移前一致
            throw new BusinessException("账号已被禁用，请联系管理员");
        } catch (AuthenticationException e) {
            // 账号不存在/密码错误（DaoAuthenticationProvider 已统一为 BadCredentialsException）
            throw new BusinessException("账号或密码错误");
        }
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        LoginVO vo = new LoginVO();
        vo.setToken(jwtTokenService.issue(loginUser.userId()));
        return vo;
    }

    /**
     * 登出：撤销当前请求令牌（jti 入 Redis 黑名单，TTL=剩余有效期）
     */
    @Override
    public void logout() {
        // 登出：把当前请求令牌的 jti 撤入黑名单（TTL=剩余有效期），即时失效
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getDetails() instanceof JwtTokenService.JwtPayload payload) {
            tokenRevocationService.revokeToken(payload.jti(), payload.expiresAtMillis());
        }
    }

    /**
     * 当前登录人信息（含角色码/权限标识/菜单树）
     *
     * @return 用户信息视图
     */
    @Override
    public UserInfoVO getInfo() {
        Long userId = SecurityUtils.getCurrentUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        List<String> roleCodes = roleMapper.selectRoleCodesByUserId(userId);
        List<String> permissions = menuMapper.selectPermsByUserId(userId);
        List<MenuTreeVO> menus = menuService.buildUserMenuTree(userId);

        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setName(user.getName());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setStatus(user.getStatus());
        vo.setRoles(roleCodes);
        vo.setPermissions(permissions);
        vo.setMenus(menus);
        return vo;
    }

    @Override
    public UserInfoVO getProfile() {
        // 个人信息即当前登录用户资料，复用 getInfo（已含 email）
        return getInfo();
    }

    /**
     * 更新当前登录人资料（仅手机号/邮箱）
     *
     * @param dto 资料更新入参
     */
    @Override
    public void updateProfile(ProfileUpdateDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 仅允许修改手机号与邮箱（非必填），其余字段保持不变
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /**
     * 修改当前登录人密码（校验原密码）
     *
     * @param dto 改密入参（原密码/新密码）
     */
    @Override
    public void changePassword(ChangePasswordDTO dto) {
        Long userId = SecurityUtils.getCurrentUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }
}
