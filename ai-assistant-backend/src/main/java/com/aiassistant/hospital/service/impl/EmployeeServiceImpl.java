package com.aiassistant.hospital.service.impl;

import com.aiassistant.common.constant.StatusConstants;
import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.security.DataScope;
import com.aiassistant.common.security.DataScopeResolver;
import com.aiassistant.common.support.NameLookup;
import com.aiassistant.common.support.PasswordSupport;
import com.aiassistant.common.support.StatusValidator;
import com.aiassistant.common.support.UserRoleBinder;
import com.aiassistant.hospital.dto.EmployeeQueryDTO;
import com.aiassistant.hospital.dto.EmployeeSaveDTO;
import com.aiassistant.hospital.dto.EmployeeUpdateDTO;
import com.aiassistant.hospital.entity.Department;
import com.aiassistant.hospital.entity.Hospital;
import com.aiassistant.hospital.mapper.DepartmentMapper;
import com.aiassistant.hospital.mapper.HospitalMapper;
import com.aiassistant.hospital.service.EmployeeService;
import com.aiassistant.hospital.vo.EmployeeVO;
import com.aiassistant.system.entity.SysRole;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysRoleMapper;
import com.aiassistant.system.mapper.SysUserMapper;
import com.aiassistant.system.mapper.SysUserRoleMapper;
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
import java.util.stream.Collectors;

/**
 * 医院员工服务实现
 * <p>
 * 数据权限：
 * <ul>
 *     <li>列表按 DataScope 过滤 sys_user（院长/管理员全部，科室主任仅本科室）</li>
 *     <li>新增/编辑/删除/改状态/重置密码 均做范围校验：科室主任不可跨科室操作员工</li>
 *     <li>科室主任新增员工时，强制归属本科室（医院由科室推导），前端传参无效</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final PasswordEncoder passwordEncoder;
    private final DataScopeResolver dataScopeResolver;

    /**
     * 分页查询员工列表（按当前登录人数据范围过滤）
     *
     * @param query 分页与筛选条件
     * @return 员工分页结果
     */
    @Override
    public PageResult<EmployeeVO> pageEmployees(EmployeeQueryDTO query) {
        DataScope scope = dataScopeResolver.resolve();
        Page<SysUser> page = new Page<>(query.getPageNum(), query.getPageSize());
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        scope.applyTo(qw, SysUser::getHospitalId, SysUser::getDepartmentId, SysUser::getId);
        if (scope.isHospital()) {
            // 院长：锁定本院，仅允许在本院内按科室二次筛选（前端传别的医院无效）
            qw.eq(SysUser::getHospitalId, scope.getHospitalId());
            if (query.getDepartmentId() != null) {
                qw.eq(SysUser::getDepartmentId, query.getDepartmentId());
            }
        } else if (scope.isAll()) {
            if (query.getHospitalId() != null) {
                qw.eq(SysUser::getHospitalId, query.getHospitalId());
            }
            if (query.getDepartmentId() != null) {
                qw.eq(SysUser::getDepartmentId, query.getDepartmentId());
            }
        }
        if (StringUtils.hasText(query.getKeyword())) {
            qw.like(SysUser::getUsername, query.getKeyword())
                    .or().like(SysUser::getName, query.getKeyword())
                    .or().like(SysUser::getPhone, query.getKeyword());
        }
        qw.orderByDesc(SysUser::getId);
        userMapper.selectPage(page, qw);
        return PageResult.of(page, enrich(page.getRecords()));
    }

    @Override
    public EmployeeVO get(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BusinessException("员工不存在");
        }
        assertReadable(u);
        return enrich(List.of(u)).get(0);
    }

    /**
     * 新增员工（按数据范围强制归属，密码为空回退默认密码，并绑定角色）
     *
     * @param dto 员工保存入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(EmployeeSaveDTO dto) {
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername())) > 0) {
            throw new BusinessException("账号已存在");
        }
        SysUser u = new SysUser();
        u.setUsername(dto.getUsername());
        u.setPassword(PasswordSupport.encodeOrDefault(dto.getPassword(), passwordEncoder));
        u.setName(dto.getName());
        u.setPhone(dto.getPhone());
        u.setEmail(dto.getEmail());
        u.setStatus(dto.getStatus() == null ? StatusConstants.ENABLED : dto.getStatus());

        // 数据范围：院长强制本院、主任强制本科室（医院由科室推导），均忽略前端越权传参
        DataScope scope = dataScopeResolver.resolve();
        if (scope.isHospital()) {
            u.setHospitalId(scope.getHospitalId());
            u.setDepartmentId(dto.getDepartmentId());
        } else if (scope.isDepartment()) {
            u.setDepartmentId(scope.getDepartmentId());
            Department d = departmentMapper.selectById(scope.getDepartmentId());
            u.setHospitalId(d == null ? null : d.getHospitalId());
        } else {
            u.setHospitalId(dto.getHospitalId());
            u.setDepartmentId(dto.getDepartmentId());
        }
        u.setCreateTime(LocalDateTime.now());
        u.setUpdateTime(LocalDateTime.now());
        userMapper.insert(u);
        UserRoleBinder.bind(userRoleMapper, u.getId(), dto.getRoleIds());
    }

    /**
     * 更新员工（按数据范围收敛归属）并重建角色绑定
     *
     * @param id 员工 ID
     * @param dto 员工更新入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, EmployeeUpdateDTO dto) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BusinessException("员工不存在");
        }
        assertWritable(u);

        DataScope scope = dataScopeResolver.resolve();
        if (scope.isHospital()) {
            // 院长：锁定本院，科室可调整（科室须属本院，前端越权传参无效）
            u.setHospitalId(scope.getHospitalId());
            u.setDepartmentId(dto.getDepartmentId());
        } else if (scope.isDepartment()) {
            // 科室主任不可将员工移出本科室
            u.setDepartmentId(scope.getDepartmentId());
            Department d = departmentMapper.selectById(scope.getDepartmentId());
            u.setHospitalId(d == null ? null : d.getHospitalId());
        } else {
            u.setHospitalId(dto.getHospitalId());
            u.setDepartmentId(dto.getDepartmentId());
        }
        u.setName(dto.getName());
        u.setPhone(dto.getPhone());
        u.setEmail(dto.getEmail());
        u.setStatus(dto.getStatus());
        u.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(u);
        UserRoleBinder.bind(userRoleMapper, id, dto.getRoleIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BusinessException("员工不存在");
        }
        assertWritable(u);
        userMapper.deleteById(id);
        userRoleMapper.deleteByUserId(id);
    }

    /**
     * 变更员工启用状态（需在数据范围内）
     *
     * @param id 员工 ID
     * @param status 状态值（1 启用 / 0 禁用）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        StatusValidator.checkEnabledDisabled(status, "非法的状态值");
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BusinessException("员工不存在");
        }
        assertWritable(u);
        u.setStatus(status);
        u.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(u);
    }

    /**
     * 重置员工密码（为空回退默认密码，需在数据范围内）
     *
     * @param id 员工 ID
     * @param newPassword 新密码明文（可空）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String newPassword) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BusinessException("员工不存在");
        }
        assertWritable(u);
        PasswordSupport.resetPassword(u, newPassword, passwordEncoder);
        userMapper.updateById(u);
    }

    /**
     * 读权限：本范围不可见则拒绝
     *
     * @param u 员工
     */
    private void assertReadable(SysUser u) {
        dataScopeResolver.resolve().assertCanAccess(u.getHospitalId(), u.getDepartmentId(), u.getId(), "无权查看该员工");
    }

    /**
     * 写权限：目标员工必须在当前登录人数据范围内
     *
     * @param u 员工
     */
    private void assertWritable(SysUser u) {
        dataScopeResolver.resolve().assertCanAccess(u.getHospitalId(), u.getDepartmentId(), u.getId(), "无权操作该员工");
    }

    /**
     * 批量回填医院名与科室名，并补齐角色信息
     *
     * @param list 员工列表
     * @return 员工视图列表
     */
    private List<EmployeeVO> enrich(List<SysUser> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> hospitalNames = NameLookup.namesByIds(
                list.stream().map(SysUser::getHospitalId).toList(), Hospital::getId, Hospital::getName, hospitalMapper::selectBatchIds);
        Map<Long, String> deptNames = NameLookup.namesByIds(
                list.stream().map(SysUser::getDepartmentId).toList(), Department::getId, Department::getName, departmentMapper::selectBatchIds);
        return list.stream().map(u -> {
            EmployeeVO vo = new EmployeeVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setName(u.getName());
            vo.setPhone(u.getPhone());
            vo.setEmail(u.getEmail());
            vo.setStatus(u.getStatus());
            vo.setHospitalId(u.getHospitalId());
            vo.setHospitalName(u.getHospitalId() == null ? null : hospitalNames.get(u.getHospitalId()));
            vo.setDepartmentId(u.getDepartmentId());
            vo.setDepartmentName(u.getDepartmentId() == null ? null : deptNames.get(u.getDepartmentId()));
            List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(u.getId());
            vo.setRoleIds(roleIds);
            if (!roleIds.isEmpty()) {
                List<SysRole> roles = roleMapper.selectByUserId(u.getId());
                vo.setRoleNames(roles.stream().map(SysRole::getRoleName).collect(Collectors.joining(",")));
            }
            vo.setCreateTime(u.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }
}
