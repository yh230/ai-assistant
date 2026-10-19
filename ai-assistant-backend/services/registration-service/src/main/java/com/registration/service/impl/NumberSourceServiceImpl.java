package com.registration.service.impl;

import com.api.client.UserClient;
import com.api.dto.UserBriefDTO;
import com.common.exception.BusinessException;
import com.common.result.Result;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.registration.entity.DoctorSchedule;
import com.registration.entity.NumberSource;
import com.registration.enums.Period;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.service.NumberSourceService;
import com.registration.support.ScheduleLock;
import com.registration.vo.NumberSourceVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 号源池服务实现；号源按排班生成，status 两态（1空闲/2占用）
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NumberSourceServiceImpl implements NumberSourceService {

    private final NumberSourceMapper numberSourceMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final UserClient userClient;
    private final DataScopeResolver dataScopeResolver;
    private final ScheduleLock scheduleLock;
    private final TransactionTemplate txTemplate;

    /**
     * 查询号源池列表（按当前登录人数据范围过滤）
     *
     * @param hospitalId 医院 ID（可空；内部调用按院约束）
     * @param doctorUserId 医生用户 ID（可空）
     * @param departmentId 科室 ID（可空）
     * @param date 出诊日期（可空）
     * @param period 时段（可空）
     * @return 号源池列表
     */
    @Override
    public List<NumberSourceVO> listPools(Long hospitalId, Long doctorUserId, Long departmentId, LocalDate date, Integer period) {
        DataScope scope = dataScopeResolver.resolve();
        LambdaQueryWrapper<DoctorSchedule> qw = new LambdaQueryWrapper<>();
        scope.applyTo(qw, DoctorSchedule::getHospitalId, DoctorSchedule::getDepartmentId, DoctorSchedule::getDoctorUserId);
        if (hospitalId != null) qw.eq(DoctorSchedule::getHospitalId, hospitalId);
        if (scope.isAll() && departmentId != null) qw.eq(DoctorSchedule::getDepartmentId, departmentId);
        if (doctorUserId != null) qw.eq(DoctorSchedule::getDoctorUserId, doctorUserId);
        if (date != null) qw.eq(DoctorSchedule::getScheduleDate, date);
        if (period != null) qw.eq(DoctorSchedule::getPeriod, period);
        qw.eq(DoctorSchedule::getStatus, 1);
        qw.orderByAsc(DoctorSchedule::getScheduleDate).orderByAsc(DoctorSchedule::getId);
        List<DoctorSchedule> schedules = scheduleMapper.selectList(qw);
        if (schedules.isEmpty()) return List.of();

        Set<Long> scheduleIds = schedules.stream().map(DoctorSchedule::getId).collect(Collectors.toSet());
        List<NumberSource> allSources = numberSourceMapper.selectList(
                new LambdaQueryWrapper<NumberSource>().in(NumberSource::getScheduleId, scheduleIds));
        Map<Long, List<NumberSource>> bySchedule = allSources.stream()
                .collect(Collectors.groupingBy(NumberSource::getScheduleId));
        Map<Long, String> uNames = doctorNames(
                schedules.stream().map(DoctorSchedule::getDoctorUserId).toList());

        List<NumberSourceVO> result = new ArrayList<>();
        for (DoctorSchedule s : schedules) {
            List<NumberSource> sources = bySchedule.getOrDefault(s.getId(), List.of());
            NumberSourceVO vo = buildPool(s, sources, uNames);
            result.add(vo);
        }
        return result;
    }

    @Override
    public NumberSourceVO detail(Long scheduleId) {
        DoctorSchedule s = requireSchedule(scheduleId);
        dataScopeResolver.resolveRequired()
                .assertCanAccess(s.getHospitalId(), s.getDepartmentId(), s.getDoctorUserId(), "无权查看该号源");
        List<NumberSource> sources = numberSourceMapper.selectList(
                new LambdaQueryWrapper<NumberSource>().eq(NumberSource::getScheduleId, scheduleId)
                        .orderByAsc(NumberSource::getSeqNo));
        Map<Long, String> uNames = doctorNames(List.of(s.getDoctorUserId()));
        return buildPool(s, sources, uNames);
    }

    /**
     * 按 ID 查询排班，不存在时抛出业务异常
     *
     * @param scheduleId 排班 ID
     * @return 排班实体
     */
    private DoctorSchedule requireSchedule(Long scheduleId) {
        DoctorSchedule s = scheduleMapper.selectById(scheduleId);
        if (s == null) throw new BusinessException("排班不存在");
        return s;
    }

    @Override
    public void generateForSchedule(DoctorSchedule schedule) {
        // 与预约抢号共用排班锁，避免重生成按旧快照算 maxSeq 后与并发占号互相覆盖。
        // 锁必须覆盖事务提交：事务由 TransactionTemplate 在锁内开启/提交，提交后才释放锁；
        // 若在外层事务中调用（save/update），内层 REQUIRED 直接加入同一事务，仍由外层锁兜底。
        scheduleLock.withLock(schedule.getId(), () ->
                txTemplate.execute(status -> {
                    doGenerateForSchedule(schedule);
                    return null;
                }));
    }

    /**
     * 实际重生成逻辑（调用方须已持有排班锁）
     *
     * @param schedule 排班
     */
    private void doGenerateForSchedule(DoctorSchedule schedule) {
        if (schedule.getTotalLimit() == null || schedule.getTotalLimit() <= 0) {
            return; // 无号源总数，不生成
        }
        // 释放该排班下空闲号源（占用中的保留，归属既有挂号记录）
        numberSourceMapper.delete(new LambdaQueryWrapper<NumberSource>()
                .eq(NumberSource::getScheduleId, schedule.getId())
                .eq(NumberSource::getStatus, 1));
        // 已有最大序号（含占用），避免 seqNo 冲突
        Integer maxSeq = numberSourceMapper.selectList(new LambdaQueryWrapper<NumberSource>()
                        .eq(NumberSource::getScheduleId, schedule.getId())
                        .select(NumberSource::getSeqNo)).stream()
                .map(NumberSource::getSeqNo).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0);
        int total = schedule.getTotalLimit();
        for (int seq = maxSeq + 1; seq <= total; seq++) {
            NumberSource ns = new NumberSource();
            ns.setHospitalId(schedule.getHospitalId());
            ns.setDepartmentId(schedule.getDepartmentId());
            ns.setDoctorUserId(schedule.getDoctorUserId());
            ns.setScheduleId(schedule.getId());
            ns.setScheduleDate(schedule.getScheduleDate());
            ns.setPeriod(schedule.getPeriod());
            ns.setSeqNo(seq);
            ns.setStatus(1); // 空闲
            numberSourceMapper.insert(ns);
        }
    }

    /**
     * 组装号源池视图（含号源总数、已用/剩余与号源明细）
     *
     * @param s 排班
     * @param sources 该排班下的号源列表
     * @param uNames 医生用户 ID 到姓名的映射
     * @return 号源池视图
     */
    private NumberSourceVO buildPool(DoctorSchedule s, List<NumberSource> sources, Map<Long, String> uNames) {
        int total = s.getTotalLimit() == null ? 0 : s.getTotalLimit();
        int used = (int) sources.stream().filter(x -> x.getStatus() != null && x.getStatus() == 2).count();
        NumberSourceVO vo = new NumberSourceVO();
        vo.setScheduleId(s.getId());
        vo.setDoctorUserId(s.getDoctorUserId());
        vo.setDoctorName(uNames.get(s.getDoctorUserId()));
        vo.setScheduleDate(s.getScheduleDate());
        vo.setPeriod(s.getPeriod());
        vo.setPeriodText(Period.of(s.getPeriod()).getDesc());
        vo.setTotalLimit(total);
        vo.setUsedCount(used);
        vo.setRemainingCount(Math.max(0, total - used));
        vo.setItems(sources.stream().map(x -> {
            NumberSourceVO.SourceItem item = new NumberSourceVO.SourceItem();
            item.setId(x.getId());
            item.setSeqNo(x.getSeqNo());
            item.setStatus(x.getStatus());
            item.setStatusText(x.getStatus() == 1 ? "空闲" : "已占用");
            item.setRegistrationId(x.getRegistrationId());
            return item;
        }).collect(Collectors.toList()));
        return vo;
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
