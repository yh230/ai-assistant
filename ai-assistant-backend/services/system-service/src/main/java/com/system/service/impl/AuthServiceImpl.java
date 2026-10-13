package com.system.service.impl;

import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.ChangePasswordDTO;
import com.api.dto.MenuNodeDTO;
import com.api.dto.ProfileUpdateDTO;
import com.common.exception.BusinessException;
import com.common.jwt.JwtTokenService;
import com.common.security.SecurityUtils;
import com.system.dto.LoginRequest;
import com.system.entity.SysUser;
import com.system.mapper.SysMenuMapper;
import com.system.mapper.SysRoleMapper;
import com.system.mapper.SysUserMapper;
import com.system.service.AuthService;
import com.system.service.MenuService;
import com.system.service.UserService;
import com.system.vo.LoginVO;
import com.system.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务实现
 * <p>
 * 登录凭据校验复用 {@link UserService#authenticate(AuthRequest)}（与内部接口同源），
 * 通过后签发 JWT；登出撤销令牌逻辑随 auth 服务割接落地
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
    private final UserService userService;
    private final JwtTokenService jwtTokenService;

    /**
     * 登录认证（账号密码校验下沉 UserService，通过后签发 JWT）
     *
     * @param request 登录入参（账号/密码）
     * @return 登录结果（JWT 令牌）
     */
    @Override
    public LoginVO login(LoginRequest request) {
        // 账号不存在/密码错误/账号禁用时由 authenticate 抛业务异常（文案与旧版一致）
        AuthRequest authRequest = new AuthRequest();
        authRequest.setUsername(request.getUsername());
        authRequest.setPassword(request.getPassword());
        AuthInfoDTO authInfo = userService.authenticate(authRequest);
        LoginVO vo = new LoginVO();
        vo.setToken(jwtTokenService.issue(authInfo.getUser().getId()));
        return vo;
    }

    /**
     * 登出：撤销当前请求令牌（jti 入 Redis 黑名单，TTL=剩余有效期）
     * <p>
     * 快照版认证链的 details 为 UserSnapshot（不含 jti），无法按 jti 撤销，
     * 令牌撤销逻辑待 auth 服务割接时随认证链一并实现
     */
    @Override
    public void logout() {
        // 快照版认证链未暴露 jti，暂无可用撤销手段
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
        List<MenuNodeDTO> menus = menuService.buildUserMenuTree(userId);

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
