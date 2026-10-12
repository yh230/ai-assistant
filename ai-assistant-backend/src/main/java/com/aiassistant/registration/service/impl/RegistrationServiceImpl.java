package com.aiassistant.registration.service.impl;

import com.aiassistant.common.exception.BusinessException;
import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.security.DataScope;
import com.aiassistant.common.security.DataScopeResolver;
import com.aiassistant.common.support.DimensionStatsSupport;
import com.aiassistant.common.support.NameLookup;
import com.aiassistant.hospital.entity.Department;
import com.aiassistant.hospital.entity.Hospital;
import com.aiassistant.registration.dto.AppointmentCreateDTO;
import com.aiassistant.registration.dto.RegistrationQueryDTO;
import com.aiassistant.registration.dto.WalkInRegisterDTO;
import com.aiassistant.registration.entity.DoctorSchedule;
import com.aiassistant.registration.entity.NumberSource;
import com.aiassistant.registration.entity.Registration;
import com.aiassistant.registration.enums.PayStatus;
import com.aiassistant.registration.enums.Period;
import com.aiassistant.registration.enums.RegistrationStatus;
import com.aiassistant.registration.enums.SourceType;
import com.aiassistant.registration.mapper.DoctorScheduleMapper;
import com.aiassistant.registration.mapper.NumberSourceMapper;
import com.aiassistant.registration.mapper.RegistrationMapper;
import com.aiassistant.registration.service.RegistrationService;
import com.aiassistant.registration.vo.RegistrationVO;
import com.aiassistant.system.entity.SysUser;
import com.aiassistant.system.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 挂号核心服务实现（状态机驱动）
 * <pre>
 * 状态机：已预约(1) → 已取号(2) → 已就诊(3) / 已取消(4) / 已退号(5) / 过期失效(6)
 *   - 线上预约：RESERVED，待支付；确认支付仅改 pay_status，状态不变。
 *   - 现场挂号：直接 CALLED（无 RESERVED 中间态）。
 *   - 取号：RESERVED → CALLED。
 *   - 退号：CALLED → REFUNDED（须未就诊）。
 *   - 标记就诊：CALLED → TREATED。
 *   - 取消预约：RESERVED → CANCELLED（释放号源）。
 *   - 过期：RESERVED 且超 expire_time → EXPIRED（释放号源）。
 * 所有流转经 RegistrationStatus.assertTransit 校验，非法流转直接拒绝。
 * </pre>
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationServiceImpl implements RegistrationService {

    /** 默认挂号费 */
    private static final BigDecimal DEFAULT_FEE = new BigDecimal("10.00");
    /** 预约锁定/取号时限（分钟） */
    private static final int EXPIRE_MINUTES = 15;

    private final RegistrationMapper registrationMapper;
    private final NumberSourceMapper numberSourceMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final SysUserMapper userMapper;
    private final com.aiassistant.hospital.mapper.HospitalMapper hospitalMapper;
    private final com.aiassistant.hospital.mapper.DepartmentMapper departmentMapper;
    private final DataScopeResolver dataScopeResolver;

    // ===================== 公开：患者线上预约 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegistrationVO createAppointment(AppointmentCreateDTO dto) {
        DoctorSchedule schedule = resolveSchedule(dto.getDoctorUserId(), dto.getScheduleDate(), dto.getPeriod());
        NumberSource source = pickFreeSource(schedule.getId(), dto.getSeqNo());
        Registration reg = new Registration();
        reg.setRegistrationNo(genRegistrationNo(schedule.getId(), source.getSeqNo()));
        reg.setHospitalId(schedule.getHospitalId());
        reg.setDepartmentId(schedule.getDepartmentId());
        reg.setDoctorUserId(schedule.getDoctorUserId());
        reg.setScheduleId(schedule.getId());
        reg.setNumberSourceId(source.getId());
        reg.setScheduleDate(schedule.getScheduleDate());
        reg.setPeriod(schedule.getPeriod());
        reg.setSeqNo(source.getSeqNo());
        reg.setPatientName(dto.getPatientName());
        reg.setPatientPhone(dto.getPatientPhone());
        reg.setPatientIdCard(dto.getPatientIdCard());
        reg.setSourceType(SourceType.ONLINE.getCode());
        reg.setPayStatus(PayStatus.UNPAID.getCode());
        reg.setPayAmount(DEFAULT_FEE);
        reg.setStatus(RegistrationStatus.RESERVED.getCode());
        reg.setExpireTime(LocalDateTime.now().plusMinutes(EXPIRE_MINUTES));
        reg.setCreateTime(LocalDateTime.now());
        reg.setUpdateTime(LocalDateTime.now());
        registrationMapper.insert(reg);

        // 锁定号源
        source.setStatus(2);
        source.setRegistrationId(reg.getId());
        numberSourceMapper.updateById(source);
        // 占用数 +1
        incrRegisteredCount(schedule.getId(), 1);

        return enrich(List.of(reg)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegistrationVO confirmPay(Long id, String idCard) {
        Registration reg = loadRegistration(id);
        verifyPatient(reg, idCard);
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        if (cur != RegistrationStatus.RESERVED) {
            throw new BusinessException("当前状态不可支付（仅已预约可支付）");
        }
        reg.setPayStatus(PayStatus.PAID.getCode());
        reg.setPayTime(LocalDateTime.now());
        reg.setUpdateTime(LocalDateTime.now());
        registrationMapper.updateById(reg);
        return enrich(List.of(reg)).get(0);
    }

    @Override
    public List<RegistrationVO> patientQuery(String phone, String idCard, String registrationNo) {
        if (!StringUtils.hasText(registrationNo)
                && !(StringUtils.hasText(phone) && StringUtils.hasText(idCard))) {
            throw new BusinessException("请提供预约号，或手机号+身份证查询");
        }
        LambdaQueryWrapper<Registration> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(registrationNo)) {
            qw.eq(Registration::getRegistrationNo, registrationNo);
        } else {
            qw.eq(Registration::getPatientPhone, phone);
            qw.eq(Registration::getPatientIdCard, idCard);
        }
        qw.orderByDesc(Registration::getCreateTime);
        return enrich(registrationMapper.selectList(qw));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegistrationVO patientCancel(Long id, String idCard, String reason) {
        Registration reg = loadRegistration(id);
        verifyPatient(reg, idCard);
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.CANCELLED, "患者取消预约");
        // 释放号源
        releaseSource(reg.getNumberSourceId());
        incrRegisteredCount(reg.getScheduleId(), -1);
        reg.setStatus(RegistrationStatus.CANCELLED.getCode());
        reg.setRemark(appendReason(reg.getRemark(), "患者取消：" + (reason == null ? "" : reason)));
        reg.setUpdateTime(LocalDateTime.now());
        registrationMapper.updateById(reg);
        return enrich(List.of(reg)).get(0);
    }

    // ===================== 员工：现场挂号 / 取号 / 退号 / 就诊 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegistrationVO walkIn(WalkInRegisterDTO dto) {
        DoctorSchedule schedule = resolveSchedule(dto.getDoctorUserId(), dto.getScheduleDate(), dto.getPeriod());
        NumberSource source = pickFreeSource(schedule.getId(), dto.getSeqNo());
        Registration reg = new Registration();
        reg.setRegistrationNo(genRegistrationNo(schedule.getId(), source.getSeqNo()));
        reg.setHospitalId(schedule.getHospitalId());
        reg.setDepartmentId(schedule.getDepartmentId());
        reg.setDoctorUserId(schedule.getDoctorUserId());
        reg.setScheduleId(schedule.getId());
        reg.setNumberSourceId(source.getId());
        reg.setScheduleDate(schedule.getScheduleDate());
        reg.setPeriod(schedule.getPeriod());
        reg.setSeqNo(source.getSeqNo());
        reg.setPatientName(dto.getPatientName());
        reg.setPatientPhone(dto.getPatientPhone());
        reg.setPatientIdCard(dto.getPatientIdCard());
        reg.setSourceType(SourceType.WINDOW.getCode());
        reg.setPayStatus(PayStatus.PAID.getCode());
        reg.setPayAmount(dto.getPayAmount() == null ? DEFAULT_FEE : dto.getPayAmount());
        reg.setPayTime(LocalDateTime.now());
        // 现场挂号：直接已取号（无预约中间态）
        reg.setStatus(RegistrationStatus.CALLED.getCode());
        reg.setCreateTime(LocalDateTime.now());
        reg.setUpdateTime(LocalDateTime.now());
        registrationMapper.insert(reg);

        source.setStatus(2);
        source.setRegistrationId(reg.getId());
        numberSourceMapper.updateById(source);
        incrRegisteredCount(schedule.getId(), 1);
        return enrich(List.of(reg)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegistrationVO take(Long id) {
        Registration reg = loadRegistration(id);
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.CALLED, "取号");
        reg.setStatus(RegistrationStatus.CALLED.getCode());
        // 窗口/自助机取号时若未支付，视为当场收费
        if (PayStatus.UNPAID.getCode() == reg.getPayStatus()) {
            reg.setPayStatus(PayStatus.PAID.getCode());
            reg.setPayTime(LocalDateTime.now());
        }
        reg.setExpireTime(null);
        reg.setUpdateTime(LocalDateTime.now());
        registrationMapper.updateById(reg);
        return enrich(List.of(reg)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegistrationVO refund(Long id, String reason) {
        Registration reg = loadRegistration(id);
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.REFUNDED, "退号");
        // 释放号源 + 号源回收
        releaseSource(reg.getNumberSourceId());
        incrRegisteredCount(reg.getScheduleId(), -1);
        reg.setStatus(RegistrationStatus.REFUNDED.getCode());
        reg.setRefundAmount(reg.getPayAmount());
        reg.setRefundTime(LocalDateTime.now());
        reg.setRemark(appendReason(reg.getRemark(), "退号：" + (reason == null ? "" : reason)));
        reg.setUpdateTime(LocalDateTime.now());
        registrationMapper.updateById(reg);
        return enrich(List.of(reg)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegistrationVO treat(Long id) {
        Registration reg = loadRegistration(id);
        assertReadable(reg); // 医生仅能操作本人患者
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.TREATED, "标记就诊");
        reg.setStatus(RegistrationStatus.TREATED.getCode());
        reg.setTreatTime(LocalDateTime.now());
        reg.setUpdateTime(LocalDateTime.now());
        registrationMapper.updateById(reg);
        return enrich(List.of(reg)).get(0);
    }

    // ===================== 员工：查询 / 统计 =====================

    /**
     * 分页查询挂号记录（按当前登录人数据范围过滤）
     *
     * @param q 分页与筛选条件
     * @return 挂号分页结果
     */
    @Override
    public PageResult<RegistrationVO> page(RegistrationQueryDTO q) {
        DataScope scope = dataScopeResolver.resolve();
        Page<Registration> page = new Page<>(q.getPageNum(), q.getPageSize());
        LambdaQueryWrapper<Registration> qw = new LambdaQueryWrapper<>();
        scope.applyTo(qw, Registration::getHospitalId, Registration::getDepartmentId, Registration::getDoctorUserId);
        if (scope.isAll()) {
            if (q.getHospitalId() != null) qw.eq(Registration::getHospitalId, q.getHospitalId());
            if (q.getDepartmentId() != null) qw.eq(Registration::getDepartmentId, q.getDepartmentId());
        }
        if (q.getDoctorUserId() != null) qw.eq(Registration::getDoctorUserId, q.getDoctorUserId());
        if (StringUtils.hasText(q.getPatientPhone())) qw.eq(Registration::getPatientPhone, q.getPatientPhone());
        if (StringUtils.hasText(q.getPatientName())) qw.like(Registration::getPatientName, q.getPatientName());
        if (q.getStatus() != null) qw.eq(Registration::getStatus, q.getStatus());
        if (q.getPeriod() != null) qw.eq(Registration::getPeriod, q.getPeriod());
        if (q.getDateFrom() != null) qw.ge(Registration::getScheduleDate, q.getDateFrom());
        if (q.getDateTo() != null) qw.le(Registration::getScheduleDate, q.getDateTo());
        qw.orderByDesc(Registration::getCreateTime);
        registrationMapper.selectPage(page, qw);
        return PageResult.of(page, enrich(page.getRecords()));
    }

    @Override
    public RegistrationVO get(Long id) {
        Registration reg = loadRegistration(id);
        assertReadable(reg);
        return enrich(List.of(reg)).get(0);
    }

    /**
     * 按维度统计挂号数据（按当前登录人数据范围过滤）
     *
     * @param dimension 统计维度（department/status/date/doctor）
     * @return 统计结果（含 dimension 与 rows）
     */
    @Override
    public Map<String, Object> stats(String dimension) {
        DataScope scope = dataScopeResolver.resolve();
        String groupColumn;
        switch (dimension) {
            case "department" -> groupColumn = "department_id";
            case "status" -> groupColumn = "status";
            case "date" -> groupColumn = "schedule_date";
            default -> groupColumn = "doctor_user_id"; // doctor
        }
        String whereSql = scope.toWhereSql("hospital_id", "department_id", "doctor_user_id");
        List<Map<String, Object>> rows = registrationMapper.groupStats(groupColumn, whereSql);
        boolean stringDimension = "date".equals(dimension);
        return DimensionStatsSupport.assemble(dimension, rows, stringDimension, dimId -> {
            if ("department".equals(dimension)) {
                if (dimId == -1L) {
                    return "未分配";
                }
                Department d = departmentMapper.selectById(dimId);
                return d == null ? "未知科室" : d.getName();
            }
            if ("status".equals(dimension)) {
                return RegistrationStatus.of(dimId.intValue()).getDesc();
            }
            if (dimId == -1L) {
                return "未知";
            }
            SysUser u = userMapper.selectById(dimId);
            return u == null ? "未知医生" : u.getName();
        });
    }

    // ===================== 内部辅助 =====================

    private DoctorSchedule resolveSchedule(Long doctorUserId, java.time.LocalDate date, Integer period) {
        if (doctorUserId == null || date == null || period == null) {
            throw new BusinessException("请选择医生、日期与时段");
        }
        DoctorSchedule s = scheduleMapper.selectOne(new LambdaQueryWrapper<DoctorSchedule>()
                .eq(DoctorSchedule::getDoctorUserId, doctorUserId)
                .eq(DoctorSchedule::getScheduleDate, date)
                .eq(DoctorSchedule::getPeriod, period));
        if (s == null || s.getStatus() != 1) {
            throw new BusinessException("该医生当日无有效出诊排班");
        }
        return s;
    }

    private NumberSource pickFreeSource(Long scheduleId, Integer seqNo) {
        LambdaQueryWrapper<NumberSource> qw = new LambdaQueryWrapper<>();
        qw.eq(NumberSource::getScheduleId, scheduleId).eq(NumberSource::getStatus, 1);
        if (seqNo != null) {
            qw.eq(NumberSource::getSeqNo, seqNo);
        }
        qw.orderByAsc(NumberSource::getSeqNo);
        // 空闲号源可能有多条（同排班 status=1 的全部），selectOne 会抛 TooManyResultsException；
        // 取排序后首条即最小可用序号，等价于“挑一个空闲号”。
        List<NumberSource> sources = numberSourceMapper.selectList(qw);
        if (sources.isEmpty()) {
            throw new BusinessException("号源已满或该序号不可用");
        }
        return sources.get(0);
    }

    private void releaseSource(Long numberSourceId) {
        if (numberSourceId == null) return;
        NumberSource src = numberSourceMapper.selectById(numberSourceId);
        if (src != null) {
            src.setStatus(1);
            src.setRegistrationId(null);
            numberSourceMapper.updateById(src);
        }
    }

    private void incrRegisteredCount(Long scheduleId, int delta) {
        DoctorSchedule s = scheduleMapper.selectById(scheduleId);
        if (s == null) return;
        int cur = s.getRegisteredCount() == null ? 0 : s.getRegisteredCount();
        s.setRegisteredCount(Math.max(0, cur + delta));
        scheduleMapper.updateById(s);
    }

    /**
     * 读权限：本范围不可见则拒绝
     *
     * @param reg 挂号记录
     */
    private void assertReadable(Registration reg) {
        dataScopeResolver.resolve().assertCanAccess(reg.getHospitalId(), reg.getDepartmentId(), reg.getDoctorUserId(), "无权查看该挂号记录");
    }

    private void verifyPatient(Registration reg, String idCard) {
        if (!StringUtils.hasText(idCard) || !idCard.equals(reg.getPatientIdCard())) {
            throw new BusinessException("患者身份校验失败");
        }
    }

    private Registration loadRegistration(Long id) {
        Registration reg = registrationMapper.selectById(id);
        if (reg == null) throw new BusinessException("挂号记录不存在");
        return reg;
    }

    private String genRegistrationNo(Long scheduleId, Integer seqNo) {
        return "GH" + scheduleId + "-" + seqNo + "-" + System.currentTimeMillis();
    }

    private String appendReason(String old, String add) {
        if (!StringUtils.hasText(old)) return add;
        return old + "；" + add;
    }

    /**
     * 批量回填医院、科室与医生名称
     *
     * @param list 挂号记录列表
     * @return 挂号视图列表
     */
    private List<RegistrationVO> enrich(List<Registration> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, String> hNames = NameLookup.namesByIds(
                list.stream().map(Registration::getHospitalId).toList(), Hospital::getId, Hospital::getName, hospitalMapper::selectBatchIds);
        Map<Long, String> dNames = NameLookup.namesByIds(
                list.stream().map(Registration::getDepartmentId).toList(), Department::getId, Department::getName, departmentMapper::selectBatchIds);
        Map<Long, String> uNames = NameLookup.namesByIds(
                list.stream().map(Registration::getDoctorUserId).toList(), SysUser::getId, SysUser::getName, userMapper::selectBatchIds);
        return list.stream().map(r -> {
            RegistrationVO vo = new RegistrationVO();
            vo.setId(r.getId());
            vo.setRegistrationNo(r.getRegistrationNo());
            vo.setHospitalId(r.getHospitalId());
            vo.setHospitalName(r.getHospitalId() == null ? null : hNames.get(r.getHospitalId()));
            vo.setDepartmentId(r.getDepartmentId());
            vo.setDepartmentName(r.getDepartmentId() == null ? null : dNames.get(r.getDepartmentId()));
            vo.setDoctorUserId(r.getDoctorUserId());
            vo.setDoctorName(uNames.get(r.getDoctorUserId()));
            vo.setScheduleId(r.getScheduleId());
            vo.setNumberSourceId(r.getNumberSourceId());
            vo.setScheduleDate(r.getScheduleDate());
            vo.setPeriod(r.getPeriod());
            vo.setPeriodText(Period.of(r.getPeriod()).getDesc());
            vo.setSeqNo(r.getSeqNo());
            vo.setPatientName(r.getPatientName());
            vo.setPatientPhone(r.getPatientPhone());
            vo.setPatientIdCard(maskIdCard(r.getPatientIdCard()));
            vo.setSourceType(r.getSourceType());
            vo.setSourceTypeText(sourceTypeText(r.getSourceType()));
            vo.setPayStatus(r.getPayStatus());
            vo.setPayStatusText(payStatusText(r.getPayStatus()));
            vo.setPayAmount(r.getPayAmount());
            vo.setPayTime(r.getPayTime());
            vo.setStatus(r.getStatus());
            vo.setStatusText(RegistrationStatus.of(r.getStatus() == null ? 1 : r.getStatus()).getDesc());
            vo.setExpireTime(r.getExpireTime());
            vo.setTreatTime(r.getTreatTime());
            vo.setRefundAmount(r.getRefundAmount());
            vo.setRefundTime(r.getRefundTime());
            vo.setRemark(r.getRemark());
            vo.setCreateTime(r.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }

    private String maskIdCard(String idCard) {
        if (!StringUtils.hasText(idCard) || idCard.length() < 8) return idCard;
        return idCard.substring(0, 4) + "**********" + idCard.substring(idCard.length() - 4);
    }

    private String sourceTypeText(Integer t) {
        if (t == null) return "未知";
        return switch (t) {
            case 1 -> "线上预约";
            case 2 -> "现场窗口";
            case 3 -> "自助机";
            default -> "未知";
        };
    }

    private String payStatusText(Integer t) {
        if (t == null) return "未知";
        return switch (t) {
            case 0 -> "未支付";
            case 1 -> "已支付";
            case 2 -> "超时作废";
            default -> "未知";
        };
    }
}
