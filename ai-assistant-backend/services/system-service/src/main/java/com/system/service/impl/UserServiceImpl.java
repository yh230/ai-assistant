package com.system.service.impl;

import com.api.client.AuthClient;
import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.ChangePasswordDTO;
import com.api.dto.ProfileUpdateDTO;
import com.api.dto.ResetPasswordDTO;
import com.api.dto.UserBriefDTO;
import com.api.dto.UserCreateRequest;
import com.api.dto.UserPageRequest;
import com.api.dto.UserUpdateRequest;
import com.common.constant.RoleConstants;
import com.common.constant.StatusConstants;
import com.common.exception.BusinessException;
import com.common.jwt.TokenRevocationService;
import com.common.result.PageResult;
import com.common.security.SecurityUtils;
import com.common.support.StatusValidator;
import com.system.entity.SysRole;
import com.system.entity.SysUser;
import com.system.mapper.SysMenuMapper;
import com.system.mapper.SysRoleMapper;
import com.system.mapper.SysUserMapper;
import com.system.mapper.SysUserRoleMapper;
import com.system.service.MenuService;
import com.system.service.UserService;
import com.system.support.PasswordSupport;
import com.system.support.UserRoleBinder;
import com.system.vo.UserVO;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 员工用户服务实现
 * <p>
 * 踢下线经 {@link TokenRevocationService}；当前登录人经 {@link SecurityUtils} 获取；
 * 角色/菜单权限/医院科室绑定/禁用启用变更后经 {@link AuthClient} 失效权限快照（禁用同时踢下线）
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
    private final SysMenuMapper menuMapper;
    private final MenuService menuService;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;
    private final AuthClient authClient;

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
     * 登录凭据校验：按 username 查用户，密码 BCrypt 校验，禁用账号拒绝登录
     *
     * @param req 登录入参（账号/密码）
     * @return 权限快照数据
     */
    @Override
    public AuthInfoDTO authenticate(AuthRequest req) {
        SysUser user = userMapper.selectByUsername(req.getUsername());
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("账号或密码错误");
        }
        if (user.getStatus() == null || !StatusConstants.ENABLED.equals(user.getStatus())) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }
        return buildAuthInfo(user.getId());
    }

    /**
     * 按用户 ID 组装权限快照数据（角色码/权限标识/菜单树/医院科室绑定）
     *
     * @param userId 用户 ID
     * @return 权限快照数据
     */
    @Override
    public AuthInfoDTO buildAuthInfo(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || !StatusConstants.ENABLED.equals(user.getStatus())) {
            throw new BusinessException("用户不存在或已停用");
        }
        AuthInfoDTO dto = new AuthInfoDTO();
        dto.setUser(toBrief(user));
        dto.setRoles(roleMapper.selectRoleCodesByUserId(userId));
        dto.setPerms(menuMapper.selectPermsByUserId(userId));
        dto.setMenus(menuService.buildUserMenuTree(userId));
        dto.setHospitalId(user.getHospitalId());
        dto.setDepartmentId(user.getDepartmentId());
        return dto;
    }

    /**
     * 按 ID 批量查询用户简要信息（结果按入参顺序，不存在的 ID 跳过）
     *
     * @param ids 用户 ID 集合
     * @return 用户简要信息列表
     */
    @Override
    public List<UserBriefDTO> batchBrief(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, SysUser> map = userMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u));
        return ids.stream().map(map::get).filter(Objects::nonNull).map(this::toBrief).toList();
    }

    /**
     * 员工分页（关键字/医院/科室/角色过滤，过滤参数由调用方按数据权限传入）
     *
     * @param req 分页与筛选条件
     * @return 员工简要信息分页结果
     */
    @Override
    public PageResult<UserBriefDTO> pageBrief(UserPageRequest req) {
        Page<SysUser> page = new Page<>(req.getPageNum(), req.getPageSize());
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(req.getKeyword())) {
            qw.and(w -> w.like(SysUser::getUsername, req.getKeyword())
                    .or().like(SysUser::getName, req.getKeyword())
                    .or().like(SysUser::getPhone, req.getKeyword()));
        }
        if (req.getHospitalId() != null) {
            qw.eq(SysUser::getHospitalId, req.getHospitalId());
        }
        if (req.getDepartmentId() != null) {
            qw.eq(SysUser::getDepartmentId, req.getDepartmentId());
        }
        if (req.getRoleId() != null) {
            List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(req.getRoleId());
            if (userIds.isEmpty()) {
                return PageResult.of(0, req.getPageNum(), req.getPageSize(), List.of());
            }
            qw.in(SysUser::getId, userIds);
        }
        qw.orderByDesc(SysUser::getId);
        userMapper.selectPage(page, qw);
        List<UserBriefDTO> list = page.getRecords().stream().map(this::toBrief).toList();
        return PageResult.of(page, list);
    }

    /**
     * 按科室/医院查询医生列表（doctor 角色且启用，供排班选择医生）
     *
     * @param hospitalId 医院 ID（可空）
     * @param departmentId 科室 ID（可空）
     * @return 医生简要信息列表
     */
    @Override
    public List<UserBriefDTO> listDoctors(Long hospitalId, Long departmentId) {
        return userMapper.selectDoctors(RoleConstants.DOCTOR, hospitalId, departmentId).stream()
                .map(this::toBrief).toList();
    }

    /**
     * 新增用户（密码为空回退默认密码，绑定角色与医院/科室）
     *
     * @param dto 用户保存入参
     * @return 新用户 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUser(UserCreateRequest dto) {
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
        user.setHospitalId(dto.getHospitalId());
        user.setDepartmentId(dto.getDepartmentId());
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
        UserRoleBinder.bind(userRoleMapper, user.getId(), dto.getRoleIds());
        return user.getId();
    }

    /**
     * 更新用户并重建角色绑定；角色/绑定/状态变更后失效权限快照，禁用同时踢下线
     *
     * @param id 用户 ID
     * @param dto 用户更新入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(Long id, UserUpdateRequest dto) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("员工不存在");
        }
        user.setName(dto.getName());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setStatus(dto.getStatus());
        // 重置密码场景（hospital 经 Feign 传入）：null 不改密码，空回退默认密码
        if (dto.getPassword() != null) {
            user.setPassword(PasswordSupport.encodeOrDefault(dto.getPassword(), passwordEncoder));
        }
        // 医院/科室绑定仅在有传值时覆盖（系统管理端不传绑定字段，医院端按数据权限收敛后传入）
        if (dto.getHospitalId() != null) {
            user.setHospitalId(dto.getHospitalId());
        }
        if (dto.getDepartmentId() != null) {
            user.setDepartmentId(dto.getDepartmentId());
        }
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        // 角色绑定仅在显式传入时重建（hospital 改状态/重置密码等部分更新不传角色，避免误清绑定）
        if (dto.getRoleIds() != null) {
            UserRoleBinder.bind(userRoleMapper, id, dto.getRoleIds());
        }
        evictAuth(id);
        if (StatusConstants.DISABLED.equals(dto.getStatus())) {
            kickout(id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id) {
        if (id.equals(getCurrentUserId())) {
            throw new BusinessException("不能删除当前登录账号");
        }
        userMapper.deleteById(id);
        userRoleMapper.deleteByUserId(id);
        evictAuth(id);
    }

    /**
     * 变更用户启用状态（禁止禁用当前登录账号）；禁用后失效快照并踢下线
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
        evictAuth(id);
        if (StatusConstants.DISABLED.equals(status)) {
            kickout(id);
        }
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
        // 撤销该用户当前全部令牌（签发时间阈值），下次请求即 401 跳登录
        tokenRevocationService.kickout(id);
    }

    /**
     * 更新用户联系方式（仅手机号/邮箱）
     *
     * @param id 用户 ID
     * @param dto 资料更新入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long id, ProfileUpdateDTO dto) {
        SysUser user = userMapper.selectById(id);
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
     * 修改密码（校验原密码）
     *
     * @param id 用户 ID
     * @param dto 改密入参（原密码/新密码）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long id, ChangePasswordDTO dto) {
        SysUser user = userMapper.selectById(id);
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

    private UserBriefDTO toBrief(SysUser user) {
        UserBriefDTO dto = new UserBriefDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setName(user.getName());
        dto.setPhone(user.getPhone());
        dto.setEmail(user.getEmail());
        dto.setStatus(user.getStatus());
        dto.setHospitalId(user.getHospitalId());
        dto.setDepartmentId(user.getDepartmentId());
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(user.getId());
        dto.setRoleIds(roleIds);
        if (!roleIds.isEmpty()) {
            List<SysRole> roles = roleMapper.selectByUserId(user.getId());
            dto.setRoleNames(roles.stream().map(SysRole::getRoleName).collect(Collectors.joining(",")));
        }
        return dto;
    }

    /**
     * 失效指定用户的权限快照（auth 服务不可用不影响本服务主流程）
     *
     * @param userId 用户 ID
     */
    private void evictAuth(Long userId) {
        try {
            authClient.evict(userId);
        } catch (Exception e) {
            log.warn("失效用户权限快照失败: userId={}, {}", userId, e.getMessage());
        }
    }

    /**
     * 强制指定用户下线（auth 服务不可用不影响本服务主流程）
     *
     * @param userId 用户 ID
     */
    private void kickout(Long userId) {
        try {
            authClient.kickout(userId);
        } catch (Exception e) {
            log.warn("强制用户下线失败: userId={}, {}", userId, e.getMessage());
        }
    }

    /**
     * 获取当前登录用户 ID（未登录返回 null）
     *
     * @return 用户 ID 或 null
     */
    private Long getCurrentUserId() {
        // 从 SecurityContext 取 userId，未登录时返回 null
        return SecurityUtils.getCurrentUserIdOrNull();
    }
}
