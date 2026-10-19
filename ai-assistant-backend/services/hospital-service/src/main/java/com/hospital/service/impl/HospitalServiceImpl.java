package com.hospital.service.impl;

import com.api.dto.OrgBriefDTO;
import com.common.constant.StatusConstants;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.common.support.StatusValidator;
import com.hospital.dto.HospitalSaveDTO;
import com.hospital.entity.Hospital;
import com.hospital.mapper.DepartmentMapper;
import com.hospital.mapper.HospitalMapper;
import com.hospital.service.HospitalService;
import com.hospital.vo.HospitalVO;
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
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 医院管理服务实现
 * <p>
 * 医院列表/下拉按 {@link DataScopeResolver} 过滤 —— 院长(hospital_director)仅可见
 * 本人所属医院；超级管理员(admin)可见全部。该接口需 hospital:manage 权限，科室主任/普通员工本身无权进入。
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalServiceImpl implements HospitalService {

    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final DataScopeResolver dataScopeResolver;

    /**
     * 分页查询医院列表（按当前登录人数据范围过滤）
     *
     * @param keyword 关键字（医院名，可空）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 医院分页结果
     */
    @Override
    public PageResult<HospitalVO> pageHospitals(String keyword, long pageNum, long pageSize) {
        DataScope scope = dataScopeResolver.resolve();
        Page<Hospital> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Hospital> qw = new LambdaQueryWrapper<>();
        // 院长：仅本人所属医院
        if (scope.isHospital()) {
            qw.eq(Hospital::getId, scope.getHospitalId());
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(Hospital::getName, keyword);
        }
        qw.orderByDesc(Hospital::getId);
        hospitalMapper.selectPage(page, qw);
        List<HospitalVO> list = page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
        return PageResult.of(page, list);
    }

    @Override
    public List<HospitalVO> listAll() {
        DataScope scope = dataScopeResolver.resolve();
        LambdaQueryWrapper<Hospital> qw = new LambdaQueryWrapper<>();
        // 院长：仅本人所属医院
        if (scope.isHospital()) {
            qw.eq(Hospital::getId, scope.getHospitalId());
        }
        qw.orderByAsc(Hospital::getName);
        List<Hospital> all = hospitalMapper.selectList(qw);
        return all.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public HospitalVO get(Long id) {
        Hospital h = requireHospital(id);
        dataScopeResolver.resolveRequired().assertCanAccess(h.getId(), null, null, "无权查看该医院");
        return toVO(h);
    }

    /**
     * 新增医院（状态为空默认启用）；仅超级管理员可创建医院
     *
     * @param dto 医院保存入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(HospitalSaveDTO dto) {
        // 医院为系统级数据，创建入口仅 admin（ALL）可用
        if (!dataScopeResolver.resolveRequired().isAll()) {
            throw new BusinessException("无权新增医院");
        }
        Hospital h = new Hospital();
        h.setName(dto.getName());
        h.setAddress(dto.getAddress());
        h.setPhone(dto.getPhone());
        h.setStatus(dto.getStatus() == null ? StatusConstants.ENABLED : dto.getStatus());
        h.setCreateTime(LocalDateTime.now());
        h.setUpdateTime(LocalDateTime.now());
        hospitalMapper.insert(h);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, HospitalSaveDTO dto) {
        Hospital h = requireHospital(id);
        dataScopeResolver.resolveRequired().assertCanAccess(h.getId(), null, null, "无权操作该医院");
        h.setName(dto.getName());
        h.setAddress(dto.getAddress());
        h.setPhone(dto.getPhone());
        h.setStatus(dto.getStatus());
        h.setUpdateTime(LocalDateTime.now());
        hospitalMapper.updateById(h);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Hospital h = requireHospital(id);
        dataScopeResolver.resolveRequired().assertCanAccess(h.getId(), null, null, "无权操作该医院");
        // 完整性校验：存在下属科室时禁止删除
        long deptCount = departmentMapper.selectCount(
                new LambdaQueryWrapper<com.hospital.entity.Department>().eq(
                        com.hospital.entity.Department::getHospitalId, id));
        if (deptCount > 0) {
            throw new BusinessException("该医院下还存在 " + deptCount + " 个科室，请先删除科室");
        }
        hospitalMapper.deleteById(id);
    }

    /**
     * 变更医院启用状态
     *
     * @param id 医院 ID
     * @param status 状态值（1 启用 / 0 禁用）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        StatusValidator.checkEnabledDisabled(status, "非法的状态值");
        Hospital h = requireHospital(id);
        dataScopeResolver.resolveRequired().assertCanAccess(h.getId(), null, null, "无权操作该医院");
        h.setStatus(status);
        h.setUpdateTime(LocalDateTime.now());
        hospitalMapper.updateById(h);
    }

    /**
     * 按 ID 查询医院，不存在时抛出业务异常
     *
     * @param id 医院 ID
     * @return 医院实体
     */
    private Hospital requireHospital(Long id) {
        Hospital h = hospitalMapper.selectById(id);
        if (h == null) {
            throw new BusinessException("医院不存在");
        }
        return h;
    }

    private HospitalVO toVO(Hospital h) {
        HospitalVO vo = new HospitalVO();
        vo.setId(h.getId());
        vo.setName(h.getName());
        vo.setAddress(h.getAddress());
        vo.setPhone(h.getPhone());
        vo.setStatus(h.getStatus());
        vo.setCreateTime(h.getCreateTime());
        return vo;
    }

    @Override
    public List<OrgBriefDTO> batchBrief(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Hospital> map = hospitalMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Hospital::getId, h -> h));
        return ids.stream().map(map::get).filter(Objects::nonNull).map(this::toBrief)
                .collect(Collectors.toList());
    }

    /**
     * 医院简要列表（公开端点：患者注册选院用，全量医院 id+name，不限数据范围）
     *
     * @return 医院简要信息列表（按名称升序）
     */
    @Override
    public List<OrgBriefDTO> listBrief() {
        LambdaQueryWrapper<Hospital> qw = new LambdaQueryWrapper<>();
        qw.orderByAsc(Hospital::getName);
        return hospitalMapper.selectList(qw).stream().map(this::toBrief)
                .collect(Collectors.toList());
    }

    private OrgBriefDTO toBrief(Hospital h) {
        OrgBriefDTO dto = new OrgBriefDTO();
        dto.setId(h.getId());
        dto.setName(h.getName());
        return dto;
    }
}
