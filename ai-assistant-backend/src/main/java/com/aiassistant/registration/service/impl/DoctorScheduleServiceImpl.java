package com.aiassistant.registration.service.impl;

import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.security.DataScope;
import com.aiassistant.common.security.DataScopeResolver;
import com.aiassistant.common.support.NameLookup;
import com.aiassistant.hospital.entity.Department;
import com.aiassistant.hospital.entity.Hospital;
import com.aiassistant.registration.dto.ScheduleQueryDTO;
import com.aiassistant.registration.dto.ScheduleSaveDTO;
import com.aiassistant.registration.entity.DoctorSchedule;
import com.aiassistant.registration.enums.Period;
import com.aiassistant.registration.mapper.DoctorScheduleMapper;
import com.aiassistant.registration.mapper.NumberSourceMapper;
import com.aiassistant.registration.service.DoctorScheduleService;
import com.aiassistant.registration.service.NumberSourceService;
import com.aiassistant.registration.vo.ScheduleVO;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 医生出诊排班服务实现（本院数据，门诊管理员维护）
 * <p>变更点：排班改了 → 作废旧号源并重生成（满足需求「排班改了，号源需要重新生成/作废旧号源」）。</p>
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorScheduleServiceImpl implements DoctorScheduleService {

    private final DoctorScheduleMapper scheduleMapper;
    private final NumberSourceMapper numberSourceMapper;
    private final NumberSourceService numberSourceService;
    private final SysUserMapper userMapper;
    private final com.aiassistant.hospital.mapper.HospitalMapper hospitalMapper;
    private final com.aiassistant.hospital.mapper.DepartmentMapper departmentMapper;
    private final DataScopeResolver dataScopeResolver;

    /**
     * 分页查询出诊排班（按当前登录人数据范围过滤）
     *
     * @param q 分页与筛选条件
     * @return 排班分页结果
     */
    @Override
    public PageResult<ScheduleVO> page(ScheduleQueryDTO q) {
        DataScope scope = dataScopeResolver.resolve();
        Page<DoctorSchedule> page = new Page<>(q.getPageNum(), q.getPageSize());
        LambdaQueryWrapper<DoctorSchedule> qw = new LambdaQueryWrapper<>();
        scope.applyTo(qw, DoctorSchedule::getHospitalId, DoctorSchedule::getDepartmentId, DoctorSchedule::getDoctorUserId);
        if (scope.isAll()) {
            if (q.getHospitalId() != null) qw.eq(DoctorSchedule::getHospitalId, q.getHospitalId());
            if (q.getDepartmentId() != null) qw.eq(DoctorSchedule::getDepartmentId, q.getDepartmentId());
        }
        if (q.getDoctorUserId() != null) qw.eq(DoctorSchedule::getDoctorUserId, q.getDoctorUserId());
        if (q.getPeriod() != null) qw.eq(DoctorSchedule::getPeriod, q.getPeriod());
        if (q.getDateFrom() != null) qw.ge(DoctorSchedule::getScheduleDate, q.getDateFrom());
        if (q.getDateTo() != null) qw.le(DoctorSchedule::getScheduleDate, q.getDateTo());
        qw.orderByDesc(DoctorSchedule::getScheduleDate).orderByAsc(DoctorSchedule::getId);
        scheduleMapper.selectPage(page, qw);
        return PageResult.of(page, enrich(page.getRecords()));
    }

    @Override
    public ScheduleVO get(Long id) {
        DoctorSchedule s = scheduleMapper.selectById(id);
        if (s == null) throw new BusinessException("排班不存在");
        return enrich(List.of(s)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScheduleVO save(ScheduleSaveDTO dto) {
        SysUser doctor = loadDoctor(dto.getDoctorUserId());
        DoctorSchedule s = new DoctorSchedule();
        copyFromDto(s, dto, doctor);
        s.setStatus(1);
        s.setRegisteredCount(0);
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        scheduleMapper.insert(s);
        numberSourceService.generateForSchedule(s);
        return enrich(List.of(s)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScheduleVO update(Long id, ScheduleSaveDTO dto) {
        DoctorSchedule s = scheduleMapper.selectById(id);
        if (s == null) throw new BusinessException("排班不存在");
        SysUser doctor = loadDoctor(dto.getDoctorUserId());
        // 若出诊医生/日期/时段/号源总数变化 → 重生成号源（作废空闲号源，保留占用）
        boolean scheduleChanged = !Objects.equals(s.getDoctorUserId(), dto.getDoctorUserId())
                || !Objects.equals(s.getScheduleDate(), dto.getScheduleDate())
                || !Objects.equals(s.getPeriod(), dto.getPeriod())
                || !Objects.equals(s.getTotalLimit(), dto.getTotalLimit());
        copyFromDto(s, dto, doctor);
        s.setUpdateTime(LocalDateTime.now());
        scheduleMapper.updateById(s);
        if (scheduleChanged) {
            numberSourceService.generateForSchedule(s);
        }
        return enrich(List.of(s)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        DoctorSchedule s = scheduleMapper.selectById(id);
        if (s == null) throw new BusinessException("排班不存在");
        // 停用排班
        s.setStatus(0);
        s.setUpdateTime(LocalDateTime.now());
        scheduleMapper.updateById(s);
        // 释放该排班下空闲号源（占用中的保留，待退号/过期再回收）
        numberSourceMapper.delete(new LambdaQueryWrapper<com.aiassistant.registration.entity.NumberSource>()
                .eq(com.aiassistant.registration.entity.NumberSource::getScheduleId, id)
                .eq(com.aiassistant.registration.entity.NumberSource::getStatus, 1));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateSources(Long scheduleId) {
        DoctorSchedule s = scheduleMapper.selectById(scheduleId);
        if (s == null) throw new BusinessException("排班不存在");
        numberSourceService.generateForSchedule(s);
    }

    @Override
    public List<ScheduleVO> listPublic(java.time.LocalDate date, Long departmentId) {
        LambdaQueryWrapper<DoctorSchedule> qw = new LambdaQueryWrapper<>();
        qw.eq(DoctorSchedule::getStatus, 1);
        if (date != null) {
            qw.eq(DoctorSchedule::getScheduleDate, date);
        } else {
            qw.ge(DoctorSchedule::getScheduleDate, java.time.LocalDate.now());
        }
        if (departmentId != null) qw.eq(DoctorSchedule::getDepartmentId, departmentId);
        qw.orderByAsc(DoctorSchedule::getScheduleDate).orderByAsc(DoctorSchedule::getId);
        return enrich(scheduleMapper.selectList(qw));
    }

    private SysUser loadDoctor(Long doctorUserId) {
        if (doctorUserId == null) throw new BusinessException("请选择医生");
        SysUser u = userMapper.selectById(doctorUserId);
        if (u == null) throw new BusinessException("医生不存在");
        if (u.getHospitalId() == null || u.getDepartmentId() == null) {
            throw new BusinessException("该医生未绑定医院/科室，无法排班");
        }
        return u;
    }

    private void copyFromDto(DoctorSchedule s, ScheduleSaveDTO dto, SysUser doctor) {
        s.setDoctorUserId(dto.getDoctorUserId());
        // 医院/科室由医生归属推导，前端不可传
        s.setHospitalId(doctor.getHospitalId());
        s.setDepartmentId(doctor.getDepartmentId());
        s.setScheduleDate(dto.getScheduleDate());
        s.setPeriod(dto.getPeriod());
        s.setTotalLimit(dto.getTotalLimit() == null ? 0 : dto.getTotalLimit());
        s.setRemark(dto.getRemark());
    }

    /**
     * 批量回填医院、科室与医生名称
     *
     * @param list 排班列表
     * @return 排班视图列表
     */
    private List<ScheduleVO> enrich(List<DoctorSchedule> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, String> hNames = NameLookup.namesByIds(
                list.stream().map(DoctorSchedule::getHospitalId).toList(), Hospital::getId, Hospital::getName, hospitalMapper::selectBatchIds);
        Map<Long, String> dNames = NameLookup.namesByIds(
                list.stream().map(DoctorSchedule::getDepartmentId).toList(), Department::getId, Department::getName, departmentMapper::selectBatchIds);
        Map<Long, String> uNames = NameLookup.namesByIds(
                list.stream().map(DoctorSchedule::getDoctorUserId).toList(), SysUser::getId, SysUser::getName, userMapper::selectBatchIds);
        return list.stream().map(s -> {
            ScheduleVO vo = new ScheduleVO();
            vo.setId(s.getId());
            vo.setHospitalId(s.getHospitalId());
            vo.setHospitalName(s.getHospitalId() == null ? null : hNames.get(s.getHospitalId()));
            vo.setDepartmentId(s.getDepartmentId());
            vo.setDepartmentName(s.getDepartmentId() == null ? null : dNames.get(s.getDepartmentId()));
            vo.setDoctorUserId(s.getDoctorUserId());
            vo.setDoctorName(uNames.get(s.getDoctorUserId()));
            vo.setScheduleDate(s.getScheduleDate());
            vo.setPeriod(s.getPeriod());
            vo.setPeriodText(Period.of(s.getPeriod()).getDesc());
            vo.setTotalLimit(s.getTotalLimit());
            vo.setRegisteredCount(s.getRegisteredCount() == null ? 0 : s.getRegisteredCount());
            vo.setRemaining(Math.max(0, (s.getTotalLimit() == null ? 0 : s.getTotalLimit()) - vo.getRegisteredCount()));
            vo.setStatus(s.getStatus());
            vo.setStatusText(s.getStatus() == 1 ? "启用" : "停用");
            vo.setRemark(s.getRemark());
            vo.setCreateTime(s.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }
}
