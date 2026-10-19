package com.registration.service.impl;

import com.api.client.OrgClient;
import com.api.client.UserClient;
import com.api.dto.OrgBriefDTO;
import com.api.dto.UserBriefDTO;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.registration.dto.ScheduleQueryDTO;
import com.registration.dto.ScheduleSaveDTO;
import com.registration.entity.DoctorSchedule;
import com.registration.entity.Registration;
import com.registration.enums.Period;
import com.registration.enums.RegistrationStatus;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.mapper.RegistrationMapper;
import com.registration.mq.RegistrationEventPublisher;
import com.registration.service.DoctorScheduleService;
import com.registration.service.NumberSourceService;
import com.registration.vo.ScheduleVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 医生出诊排班服务实现（本院数据，门诊管理员维护）
 * <p>排班变更后作废旧号源并重新生成。</p>
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
    private final UserClient userClient;
    private final OrgClient orgClient;
    private final DataScopeResolver dataScopeResolver;
    private final RegistrationMapper registrationMapper;
    private final RegistrationEventPublisher eventPublisher;

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
        DoctorSchedule s = requireSchedule(id);
        dataScopeResolver.resolveRequired()
                .assertCanAccess(s.getHospitalId(), s.getDepartmentId(), s.getDoctorUserId(), "无权查看该排班");
        return enrich(List.of(s)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScheduleVO save(ScheduleSaveDTO dto) {
        DataScope scope = dataScopeResolver.resolveRequired();
        UserBriefDTO doctor = loadDoctor(dto.getDoctorUserId());
        // 医院/科室由医生归属推导后仍需落在操作人范围内（忽略前端传值）
        scope.assertCanAccess(doctor.getHospitalId(), doctor.getDepartmentId(), doctor.getId(), "无权操作该排班");
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
        DoctorSchedule s = requireSchedule(id);
        DataScope scope = dataScopeResolver.resolveRequired();
        scope.assertCanAccess(s.getHospitalId(), s.getDepartmentId(), s.getDoctorUserId(), "无权操作该排班");
        UserBriefDTO doctor = loadDoctor(dto.getDoctorUserId());
        // 改派医生同样不得越出操作人范围
        scope.assertCanAccess(doctor.getHospitalId(), doctor.getDepartmentId(), doctor.getId(), "无权操作该排班");
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
        DoctorSchedule s = requireSchedule(id);
        dataScopeResolver.resolveRequired()
                .assertCanAccess(s.getHospitalId(), s.getDepartmentId(), s.getDoctorUserId(), "无权操作该排班");
        // 停用排班
        s.setStatus(0);
        s.setUpdateTime(LocalDateTime.now());
        scheduleMapper.updateById(s);
        // 释放该排班下空闲号源（占用中的保留，待退号/过期再回收）
        numberSourceMapper.delete(new LambdaQueryWrapper<com.registration.entity.NumberSource>()
                .eq(com.registration.entity.NumberSource::getScheduleId, id)
                .eq(com.registration.entity.NumberSource::getStatus, 1));
        // 停诊通知：对该排班下仍有效（已预约/已取号）的挂号单逐条发布事件（事务提交后发送）
        List<Registration> affected = registrationMapper.selectList(new LambdaQueryWrapper<Registration>()
                .eq(Registration::getScheduleId, id)
                .in(Registration::getStatus,
                        RegistrationStatus.RESERVED.getCode(), RegistrationStatus.CALLED.getCode()));
        eventPublisher.publishScheduleCancelled(s, affected);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateSources(Long scheduleId) {
        DoctorSchedule s = requireSchedule(scheduleId);
        numberSourceService.generateForSchedule(s);
    }

    /**
     * 按 ID 查询排班，不存在时抛出业务异常
     *
     * @param id 排班 ID
     * @return 排班实体
     */
    private DoctorSchedule requireSchedule(Long id) {
        DoctorSchedule s = scheduleMapper.selectById(id);
        if (s == null) throw new BusinessException("排班不存在");
        return s;
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

    private UserBriefDTO loadDoctor(Long doctorUserId) {
        if (doctorUserId == null) throw new BusinessException("请选择医生");
        List<UserBriefDTO> users = unwrap(userClient.batch(List.of(doctorUserId)), "查询医生失败");
        if (users.isEmpty()) {
            throw new BusinessException("医生不存在");
        }
        UserBriefDTO u = users.get(0);
        if (u.getHospitalId() == null || u.getDepartmentId() == null) {
            throw new BusinessException("该医生未绑定医院/科室，无法排班");
        }
        return u;
    }

    private void copyFromDto(DoctorSchedule s, ScheduleSaveDTO dto, UserBriefDTO doctor) {
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
        Map<Long, String> hNames = orgNames(
                list.stream().map(DoctorSchedule::getHospitalId).toList(), orgClient::hospitals);
        Map<Long, String> dNames = orgNames(
                list.stream().map(DoctorSchedule::getDepartmentId).toList(), orgClient::departments);
        Map<Long, String> uNames = doctorNames(
                list.stream().map(DoctorSchedule::getDoctorUserId).toList());
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

    /**
     * 批量回填医院/科室名称（医院中心 Feign 批量查询后组 ID→名称 映射，跳过 null ID、重复 ID 取首个）
     *
     * @param ids ID 集合（可含 null）
     * @param loader 组织简要信息批量加载函数
     * @return ID→名称 映射（无数据返回空 Map）
     */
    private Map<Long, String> orgNames(Collection<Long> ids,
            Function<List<Long>, Result<List<OrgBriefDTO>>> loader) {
        List<Long> cleanIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (cleanIds.isEmpty()) {
            return Map.of();
        }
        List<OrgBriefDTO> orgs = unwrap(loader.apply(cleanIds), "查询医院/科室信息失败");
        return orgs.stream()
                .collect(Collectors.toMap(OrgBriefDTO::getId, OrgBriefDTO::getName, (first, second) -> first));
    }

    /**
     * 批量回填医生姓名（用户中心 Feign 批量查询后组 ID→姓名 映射，跳过 null ID、重复 ID 取首个）
     *
     * @param ids 医生用户 ID 集合（可含 null）
     * @return ID→姓名 映射（无数据返回空 Map）
     */
    private Map<Long, String> doctorNames(Collection<Long> ids) {
        List<Long> cleanIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (cleanIds.isEmpty()) {
            return Map.of();
        }
        List<UserBriefDTO> users = unwrap(userClient.batch(cleanIds), "查询医生信息失败");
        return users.stream()
                .collect(Collectors.toMap(UserBriefDTO::getId, UserBriefDTO::getName, (first, second) -> first));
    }

    /**
     * 解包 Feign 返回结果（非 200 抛业务异常，文案透传）
     *
     * @param result Feign 返回
     * @param failMessage 失败兜底文案
     * @param <T> 数据类型
     * @return 业务数据
     */
    private <T> T unwrap(Result<T> result, String failMessage) {
        if (result == null || result.getCode() != 200) {
            throw new BusinessException(result == null ? failMessage : result.getMsg());
        }
        return result.getData();
    }
}
