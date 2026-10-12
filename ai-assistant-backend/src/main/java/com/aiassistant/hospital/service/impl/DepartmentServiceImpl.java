package com.aiassistant.hospital.service.impl;

import com.aiassistant.common.constant.StatusConstants;
import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.security.DataScope;
import com.aiassistant.common.security.DataScopeResolver;
import com.aiassistant.common.support.NameLookup;
import com.aiassistant.common.support.StatusValidator;
import com.aiassistant.hospital.dto.DepartmentSaveDTO;
import com.aiassistant.hospital.entity.Department;
import com.aiassistant.hospital.entity.Hospital;
import com.aiassistant.hospital.mapper.DepartmentMapper;
import com.aiassistant.hospital.mapper.HospitalMapper;
import com.aiassistant.hospital.service.DepartmentService;
import com.aiassistant.hospital.vo.DepartmentVO;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 科室管理服务实现
 * <p>
 * 列表/下拉按 {@link DataScopeResolver} 过滤 —— 院长/管理员看全部，
 * 科室主任仅看本科室，普通员工不可见（返回空）。科室主任不拥有科室维护权限（无 hospital:manage）。
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentMapper departmentMapper;
    private final HospitalMapper hospitalMapper;
    private final SysUserMapper userMapper;
    private final DataScopeResolver dataScopeResolver;

    /**
     * 分页查询科室列表（按当前登录人数据范围过滤）
     *
     * @param keyword 关键字（科室名，可空）
     * @param hospitalId 医院 ID（可空）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 科室分页结果
     */
    @Override
    public PageResult<DepartmentVO> pageDepartments(String keyword, Long hospitalId, long pageNum, long pageSize) {
        DataScope scope = dataScopeResolver.resolve();
        Page<Department> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Department> qw = new LambdaQueryWrapper<>();
        applyScope(qw, scope);
        if (scope.isAll() && hospitalId != null) {
            qw.eq(Department::getHospitalId, hospitalId);
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(Department::getName, keyword);
        }
        qw.orderByDesc(Department::getId);
        departmentMapper.selectPage(page, qw);
        return PageResult.of(page, enrich(page.getRecords()));
    }

    @Override
    public List<DepartmentVO> listByHospital(Long hospitalId) {
        DataScope scope = dataScopeResolver.resolve();
        LambdaQueryWrapper<Department> qw = new LambdaQueryWrapper<>();
        applyScope(qw, scope);
        if (scope.isAll() && hospitalId != null) {
            qw.eq(Department::getHospitalId, hospitalId);
        }
        qw.orderByAsc(Department::getName);
        return enrich(departmentMapper.selectList(qw));
    }

    @Override
    public DepartmentVO get(Long id) {
        Department d = departmentMapper.selectById(id);
        if (d == null) {
            throw new BusinessException("科室不存在");
        }
        return enrich(List.of(d)).get(0);
    }

    /**
     * 新增科室（状态为空默认启用）
     *
     * @param dto 科室保存入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(DepartmentSaveDTO dto) {
        Department d = new Department();
        d.setHospitalId(dto.getHospitalId());
        d.setName(dto.getName());
        d.setDirectorUserId(dto.getDirectorUserId());
        d.setStatus(dto.getStatus() == null ? StatusConstants.ENABLED : dto.getStatus());
        d.setCreateTime(LocalDateTime.now());
        d.setUpdateTime(LocalDateTime.now());
        departmentMapper.insert(d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, DepartmentSaveDTO dto) {
        Department d = departmentMapper.selectById(id);
        if (d == null) {
            throw new BusinessException("科室不存在");
        }
        d.setHospitalId(dto.getHospitalId());
        d.setName(dto.getName());
        d.setDirectorUserId(dto.getDirectorUserId());
        d.setStatus(dto.getStatus());
        d.setUpdateTime(LocalDateTime.now());
        departmentMapper.updateById(d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Department d = departmentMapper.selectById(id);
        if (d == null) {
            throw new BusinessException("科室不存在");
        }
        departmentMapper.deleteById(id);
    }

    /**
     * 变更科室启用状态
     *
     * @param id 科室 ID
     * @param status 状态值（1 启用 / 0 禁用）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        StatusValidator.checkEnabledDisabled(status, "非法的状态值");
        Department d = departmentMapper.selectById(id);
        if (d == null) {
            throw new BusinessException("科室不存在");
        }
        d.setStatus(status);
        d.setUpdateTime(LocalDateTime.now());
        departmentMapper.updateById(d);
    }

    private void applyScope(LambdaQueryWrapper<Department> qw, DataScope scope) {
        if (scope.isHospital()) {
            // 院长：仅本院全部科室
            qw.eq(Department::getHospitalId, scope.getHospitalId());
        } else if (scope.isDepartment()) {
            // 科室主任：仅本院全部科室（便于查看本科室与其它科室）
            qw.eq(Department::getHospitalId, scope.getHospitalId());
        } else if (scope.isSelf()) {
            // 普通员工不可见任何科室（无维护/查看权限）
            qw.eq(Department::getId, -1L);
        }
        // ALL：不加限制
    }

    /**
     * 批量回填医院名与主任名
     *
     * @param list 科室列表
     * @return 科室视图列表
     */
    private List<DepartmentVO> enrich(List<Department> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> hospitalNames = NameLookup.namesByIds(
                list.stream().map(Department::getHospitalId).toList(), Hospital::getId, Hospital::getName, hospitalMapper::selectBatchIds);
        Map<Long, String> userNames = NameLookup.namesByIds(
                list.stream().map(Department::getDirectorUserId).toList(), SysUser::getId, SysUser::getName, userMapper::selectBatchIds);
        return list.stream().map(d -> {
            DepartmentVO vo = new DepartmentVO();
            vo.setId(d.getId());
            vo.setHospitalId(d.getHospitalId());
            vo.setHospitalName(hospitalNames.get(d.getHospitalId()));
            vo.setName(d.getName());
            vo.setDirectorUserId(d.getDirectorUserId());
            vo.setDirectorName(d.getDirectorUserId() == null ? null : userNames.get(d.getDirectorUserId()));
            vo.setStatus(d.getStatus());
            vo.setCreateTime(d.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }
}
