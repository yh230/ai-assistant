package com.aiassistant.hospital.service.impl;

import com.aiassistant.common.constant.StatusConstants;
import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.security.DataScope;
import com.aiassistant.common.security.DataScopeResolver;
import com.aiassistant.common.support.StatusValidator;
import com.aiassistant.hospital.dto.HospitalSaveDTO;
import com.aiassistant.hospital.entity.Hospital;
import com.aiassistant.hospital.mapper.DepartmentMapper;
import com.aiassistant.hospital.mapper.HospitalMapper;
import com.aiassistant.hospital.service.HospitalService;
import com.aiassistant.hospital.vo.HospitalVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
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
        Hospital h = hospitalMapper.selectById(id);
        if (h == null) {
            throw new BusinessException("医院不存在");
        }
        return toVO(h);
    }

    /**
     * 新增医院（状态为空默认启用）
     *
     * @param dto 医院保存入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(HospitalSaveDTO dto) {
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
        Hospital h = hospitalMapper.selectById(id);
        if (h == null) {
            throw new BusinessException("医院不存在");
        }
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
        Hospital h = hospitalMapper.selectById(id);
        if (h == null) {
            throw new BusinessException("医院不存在");
        }
        // 完整性校验：存在下属科室时禁止删除
        long deptCount = departmentMapper.selectCount(
                new LambdaQueryWrapper<com.aiassistant.hospital.entity.Department>().eq(
                        com.aiassistant.hospital.entity.Department::getHospitalId, id));
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
        Hospital h = hospitalMapper.selectById(id);
        if (h == null) {
            throw new BusinessException("医院不存在");
        }
        h.setStatus(status);
        h.setUpdateTime(LocalDateTime.now());
        hospitalMapper.updateById(h);
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
}
