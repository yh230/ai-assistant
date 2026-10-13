package com.system.patient.service.impl;

import com.api.client.OrgClient;
import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.OrgBriefDTO;
import com.api.dto.UserBriefDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.common.constant.StatusConstants;
import com.common.exception.BusinessException;
import com.common.result.Result;
import com.system.patient.dto.PatientRegisterDTO;
import com.system.patient.entity.PatientUser;
import com.system.patient.mapper.PatientUserMapper;
import com.system.patient.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 患者账号服务实现
 * <p>
 * 患者与员工账号物理隔离：独立 patient_user 表、独立登录名（手机号）、无角色权限；
 * 注册校验手机号唯一与医院存在性（经 {@link OrgClient}），密码 BCrypt 加密存储
 *
 * @author yh
 * @date 2026-10-13
 */
@Service
@RequiredArgsConstructor
public class PatientServiceImpl implements PatientService {

    private final PatientUserMapper patientUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final OrgClient orgClient;

    /**
     * 注册患者账号（手机号唯一、医院存在性校验，密码 BCrypt 加密入库）
     *
     * @param dto 注册入参（手机号/密码/姓名/医院）
     * @return 新账号 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(PatientRegisterDTO dto) {
        // 手机号即登录名，注册前查重
        if (patientUserMapper.selectCount(
                new LambdaQueryWrapper<PatientUser>().eq(PatientUser::getPhone, dto.getPhone())) > 0) {
            throw new BusinessException("该手机号已注册");
        }
        // 医院数据在 hospital 库，经内部接口校验存在性
        Result<List<OrgBriefDTO>> hospitalResult = orgClient.hospitals(List.of(dto.getHospitalId()));
        if (hospitalResult == null || hospitalResult.getCode() != 200
                || hospitalResult.getData() == null || hospitalResult.getData().isEmpty()) {
            throw new BusinessException("所选医院不存在");
        }
        PatientUser user = new PatientUser();
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setName(dto.getName());
        user.setHospitalId(dto.getHospitalId());
        user.setStatus(StatusConstants.ENABLED);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        patientUserMapper.insert(user);
        return user.getId();
    }

    /**
     * 患者登录凭据校验（按手机号查询，禁用账号拒绝登录）
     *
     * @param req 登录入参（username 为手机号）
     * @return 权限快照数据
     */
    @Override
    public AuthInfoDTO authenticate(AuthRequest req) {
        PatientUser user = patientUserMapper.selectOne(
                new LambdaQueryWrapper<PatientUser>().eq(PatientUser::getPhone, req.getUsername()));
        if (user == null) {
            throw new BusinessException("手机号或密码错误");
        }
        if (!StatusConstants.ENABLED.equals(user.getStatus())) {
            throw new BusinessException("账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("手机号或密码错误");
        }
        return buildAuthInfo(user);
    }

    /**
     * 按患者 ID 组装快照数据（不存在或禁用返回 null）
     *
     * @param id 患者 ID
     * @return 权限快照数据或 null
     */
    @Override
    public AuthInfoDTO authInfo(Long id) {
        PatientUser user = patientUserMapper.selectById(id);
        if (user == null || !StatusConstants.ENABLED.equals(user.getStatus())) {
            return null;
        }
        return buildAuthInfo(user);
    }

    /**
     * 按 ID 批量查询患者简要信息（结果按入参顺序，不存在的 ID 跳过）
     *
     * @param ids 患者 ID 集合
     * @return 患者简要信息列表
     */
    @Override
    public List<UserBriefDTO> batchBrief(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, PatientUser> map = patientUserMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(PatientUser::getId, u -> u));
        return ids.stream().map(map::get).filter(Objects::nonNull).map(this::toBrief).toList();
    }

    /**
     * 组装患者认证快照数据（患者无角色/权限/菜单，数据范围为绑定医院）
     *
     * @param user 患者账号
     * @return 权限快照数据
     */
    private AuthInfoDTO buildAuthInfo(PatientUser user) {
        AuthInfoDTO dto = new AuthInfoDTO();
        dto.setUser(toBrief(user));
        dto.setRoles(List.of());
        dto.setPerms(List.of());
        dto.setHospitalId(user.getHospitalId());
        return dto;
    }

    /**
     * 患者账号转用户简要信息（username 取手机号）
     *
     * @param user 患者账号
     * @return 用户简要信息
     */
    private UserBriefDTO toBrief(PatientUser user) {
        UserBriefDTO dto = new UserBriefDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getPhone());
        dto.setName(user.getName());
        dto.setPhone(user.getPhone());
        dto.setStatus(user.getStatus());
        dto.setHospitalId(user.getHospitalId());
        return dto;
    }
}
