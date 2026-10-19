package com.hospital.service.impl;

import com.api.client.UserClient;
import com.api.dto.OrgBriefDTO;
import com.api.dto.UserBriefDTO;
import com.common.constant.StatusConstants;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.common.support.StatusValidator;
import com.hospital.dto.DepartmentSaveDTO;
import com.hospital.entity.Department;
import com.hospital.entity.Hospital;
import com.hospital.mapper.DepartmentMapper;
import com.hospital.mapper.HospitalMapper;
import com.hospital.service.DepartmentService;
import com.hospital.vo.DepartmentVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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
    private final UserClient userClient;
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
        Department d = requireDepartment(id);
        dataScopeResolver.resolveRequired()
                .assertCanAccess(d.getHospitalId(), d.getId(), null, "无权操作该科室");
        return enrich(List.of(d)).get(0);
    }

    /**
     * 新增科室（状态为空默认启用）；非 ALL 范围强制归属本人医院
     *
     * @param dto 科室保存入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(DepartmentSaveDTO dto) {
        DataScope scope = dataScopeResolver.resolveRequired();
        Department d = new Department();
        // 医院归属由数据范围决定，忽略前端传值
        d.setHospitalId(scope.isAll() ? dto.getHospitalId() : scope.getHospitalId());
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
        Department d = requireDepartment(id);
        DataScope scope = dataScopeResolver.resolveRequired();
        scope.assertCanAccess(d.getHospitalId(), d.getId(), null, "无权操作该科室");
        // 医院归属由数据范围决定，忽略前端传值
        d.setHospitalId(scope.isAll() ? dto.getHospitalId() : scope.getHospitalId());
        d.setName(dto.getName());
        d.setDirectorUserId(dto.getDirectorUserId());
        d.setStatus(dto.getStatus());
        d.setUpdateTime(LocalDateTime.now());
        departmentMapper.updateById(d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Department d = requireDepartment(id);
        dataScopeResolver.resolveRequired()
                .assertCanAccess(d.getHospitalId(), d.getId(), null, "无权操作该科室");
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
        Department d = requireDepartment(id);
        dataScopeResolver.resolveRequired()
                .assertCanAccess(d.getHospitalId(), d.getId(), null, "无权操作该科室");
        d.setStatus(status);
        d.setUpdateTime(LocalDateTime.now());
        departmentMapper.updateById(d);
    }

    /**
     * 按 ID 查询科室，不存在时抛出业务异常
     *
     * @param id 科室 ID
     * @return 科室实体
     */
    private Department requireDepartment(Long id) {
        Department d = departmentMapper.selectById(id);
        if (d == null) {
            throw new BusinessException("科室不存在");
        }
        return d;
    }

    @Override
    public List<OrgBriefDTO> batchBrief(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Department> map = departmentMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Department::getId, d -> d));
        return ids.stream().map(map::get).filter(Objects::nonNull).map(this::toBrief).collect(Collectors.toList());
    }

    @Override
    public List<OrgBriefDTO> listBrief(Long hospitalId) {
        LambdaQueryWrapper<Department> qw = new LambdaQueryWrapper<>();
        if (hospitalId != null) {
            qw.eq(Department::getHospitalId, hospitalId);
        }
        qw.orderByAsc(Department::getName);
        return departmentMapper.selectList(qw).stream().map(this::toBrief).collect(Collectors.toList());
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
        Map<Long, String> hospitalNames = namesByIds(
                list.stream().map(Department::getHospitalId).filter(Objects::nonNull).distinct().toList(),
                hospitalMapper::selectBatchIds, Hospital::getId, Hospital::getName);
        Map<Long, String> userNames = userNamesByIds(
                list.stream().map(Department::getDirectorUserId).filter(Objects::nonNull).distinct().toList());
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

    /**
     * 经用户中心批量查询员工 ID→姓名 映射（返回可能分批，缺失时回填为空）
     *
     * @param ids 员工 ID 集合（已去空去重）
     * @return ID→姓名 映射
     */
    private Map<Long, String> userNamesByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Result<List<UserBriefDTO>> result = userClient.batch(ids);
        List<UserBriefDTO> rows = (result != null && result.getCode() == 200 && result.getData() != null)
                ? result.getData() : List.of();
        return rows.stream().collect(Collectors.toMap(UserBriefDTO::getId, UserBriefDTO::getName, (first, second) -> first));
    }

    /**
     * 按 ID 集合批量查询 ID→名称 映射（本库 mapper，重复 ID 取首个）
     *
     * @param ids ID 集合（已去空去重）
     * @param batchLoader 批量加载函数
     * @param idGetter 实体 ID 取值函数
     * @param nameGetter 实体名称取值函数
     * @param <T> 实体类型
     * @return ID→名称 映射（无数据返回空 Map）
     */
    private <T> Map<Long, String> namesByIds(List<Long> ids, Function<Collection<Long>, List<T>> batchLoader,
            Function<T, Long> idGetter, Function<T, String> nameGetter) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return batchLoader.apply(ids).stream()
                .collect(Collectors.toMap(idGetter, nameGetter, (first, second) -> first));
    }

    private OrgBriefDTO toBrief(Department d) {
        OrgBriefDTO dto = new OrgBriefDTO();
        dto.setId(d.getId());
        dto.setName(d.getName());
        dto.setHospitalId(d.getHospitalId());
        return dto;
    }
}
