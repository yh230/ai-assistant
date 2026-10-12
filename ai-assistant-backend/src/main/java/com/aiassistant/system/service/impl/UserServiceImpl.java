package com.aiassistant.system.service.impl;

import com.aiassistant.common.constant.StatusConstants;
import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.support.PasswordSupport;
import com.aiassistant.common.support.StatusValidator;
import com.aiassistant.common.support.UserRoleBinder;
import com.aiassistant.security.SecurityUtils;
import com.aiassistant.security.TokenRevocationService;
import com.aiassistant.common.dto.ResetPasswordDTO;
import com.aiassistant.system.dto.UserSaveDTO;
import com.aiassistant.system.dto.UserUpdateDTO;
import com.aiassistant.system.entity.SysRole;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysRoleMapper;
import com.aiassistant.system.mapper.SysUserMapper;
import com.aiassistant.system.mapper.SysUserRoleMapper;
import com.aiassistant.system.service.UserService;
import com.aiassistant.system.vo.UserVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 员工用户服务实现
 * <p>
 * 变更点（由 Sa-Token 迁移）：踢下线改经 {@link TokenRevocationService}；当前登录人改经 {@link SecurityUtils} 获取
 *
 * @author yh
 * @date 2026-10-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;

    /**
     * 分页查询用户列表
     *
     * @param keyword 关键字（账号/姓名/手机号，可空）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 用户分页结果
     */
    @Override
    public PageResult<UserVO> pageUsers(String keyword, long pageNum, long pageSize) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getName, keyword)
                    .or().like(SysUser::getPhone, keyword);
        }
        qw.orderByDesc(SysUser::getId);
        userMapper.selectPage(page, qw);
        List<UserVO> list = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(page, list);
    }

    /**
     * 新增用户（密码为空回退默认密码）
     *
     * @param dto 用户保存入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createUser(UserSaveDTO dto) {
        if (userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername())) > 0) {
            throw new BusinessException("账号已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(PasswordSupport.encodeOrDefault(dto.getPassword(), passwordEncoder));
        user.setName(dto.getName());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setStatus(dto.getStatus() == null ? StatusConstants.ENABLED : dto.getStatus());
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
        UserRoleBinder.bind(userRoleMapper, user.getId(), dto.getRoleIds());
    }

    /**
     * 更新用户并重建角色绑定
     *
     * @param id 用户 ID
     * @param dto 用户更新入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(Long id, UserUpdateDTO dto) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("员工不存在");
        }
        user.setName(dto.getName());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setStatus(dto.getStatus());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        UserRoleBinder.bind(userRoleMapper, id, dto.getRoleIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id) {
        if (id.equals(getCurrentUserId())) {
            throw new BusinessException("不能删除当前登录账号");
        }
        userMapper.deleteById(id);
        userRoleMapper.deleteByUserId(id);
    }

    /**
     * 变更用户启用状态（禁止禁用当前登录账号）
     *
     * @param id 用户 ID
     * @param status 状态值（1 启用 / 0 禁用）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        StatusValidator.checkEnabledDisabled(status, "非法的账号状态");
        if (StatusConstants.DISABLED.equals(status) && id.equals(getCurrentUserId())) {
            throw new BusinessException("不能禁用当前登录账号");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("员工不存在");
        }
        user.setStatus(status);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /**
     * 重置用户密码（为空回退默认密码）
     *
     * @param id 用户 ID
     * @param dto 重置密码入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, ResetPasswordDTO dto) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("员工不存在");
        }
        PasswordSupport.resetPassword(user, dto.getNewPassword(), passwordEncoder);
        userMapper.updateById(user);
    }

    /**
     * 踢用户下线（禁止踢当前登录账号）
     *
     * @param id 用户 ID
     */
    @Override
    public void kickoutUser(Long id) {
        if (id.equals(getCurrentUserId())) {
            throw new BusinessException("不能踢下线当前登录账号");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("员工不存在");
        }
        // 变更点（由 Sa-Token 迁移）：撤销该用户当前全部令牌（签发时间阈值），下次请求即 401 跳登录
        tokenRevocationService.kickout(id);
    }

    private UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setName(user.getName());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(user.getId());
        vo.setRoleIds(roleIds);
        if (!roleIds.isEmpty()) {
            List<SysRole> roles = roleMapper.selectByUserId(user.getId());
            vo.setRoleNames(roles.stream().map(SysRole::getRoleName).collect(Collectors.joining(",")));
        }
        return vo;
    }

    /**
     * 获取当前登录用户 ID（未登录返回 null）
     *
     * @return 用户 ID 或 null
     */
    private Long getCurrentUserId() {
        // 变更点（由 Sa-Token 迁移）：从 SecurityContext 取 userId，未登录时返回 null
        return SecurityUtils.getCurrentUserIdOrNull();
    }
}
