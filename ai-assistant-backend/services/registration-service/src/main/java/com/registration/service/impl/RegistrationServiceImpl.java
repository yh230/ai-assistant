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
import com.common.support.DimensionStatsSupport;
import com.registration.dto.AppointmentCreateDTO;
import com.registration.dto.RegistrationQueryDTO;
import com.registration.dto.WalkInRegisterDTO;
import com.registration.entity.DoctorSchedule;
import com.registration.entity.NumberSource;
import com.registration.entity.Registration;
import com.registration.enums.PayStatus;
import com.registration.enums.Period;
import com.registration.enums.RegistrationStatus;
import com.registration.enums.SourceType;
import com.registration.mapper.DoctorScheduleMapper;
import com.registration.mapper.NumberSourceMapper;
import com.registration.mapper.RegistrationMapper;
import com.registration.service.RegistrationService;
import com.registration.vo.RegistrationVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 挂号核心服务实现（状态机驱动 + Redisson 分布式锁并发加固）
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
 * 并发控制：Redisson 分布式锁（全局固定获取顺序 patient → registration → schedule）
 * 覆盖事务提交；状态跃迁、号源抢占/释放、占用数增减均以条件更新兜底，全程无数据库行锁。
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
    /** 号源状态：空闲可约 */
    private static final int SOURCE_FREE = 1;
    /** 号源状态：已占用 */
    private static final int SOURCE_OCCUPIED = 2;

    /** 患者-日期锁前缀（+患者ID+日期；防同患者同日重复预约） */
    private static final String PATIENT_DAY_LOCK_PREFIX = "lock:booking:patient:";
    /** 挂号单锁前缀（+挂号单ID；防状态跃迁双写） */
    private static final String REGISTRATION_LOCK_PREFIX = "lock:registration:";
    /** 排班锁前缀（+排班ID；防号源双卖与占用数丢失更新） */
    private static final String SCHEDULE_LOCK_PREFIX = "lock:schedule:";
    /** 常规等锁时长（秒） */
    private static final long LOCK_WAIT_SECONDS = 3;
    /** 锁租期（秒，覆盖 DB 操作） */
    private static final long LOCK_LEASE_SECONDS = 15;
    /** 号源条件抢占重试上限（次） */
    private static final int CLAIM_RETRY_TIMES = 3;

    private final RegistrationMapper registrationMapper;
    private final NumberSourceMapper numberSourceMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final UserClient userClient;
    private final OrgClient orgClient;
    private final DataScopeResolver dataScopeResolver;
    private final RedissonClient redissonClient;
    private final TransactionTemplate txTemplate;

    // ===================== 公开：患者线上预约 =====================

    /**
     * 患者线上预约（锁内重复检查，条件抢占号源；固定锁序 patient → schedule）
     *
     * @param dto 预约参数
     * @return 挂号视图
     */
    @Override
    public RegistrationVO createAppointment(AppointmentCreateDTO dto) {
        DoctorSchedule schedule = resolveSchedule(dto.getDoctorUserId(), dto.getScheduleDate(), dto.getPeriod());
        Registration reg;
        if (dto.getPatientId() != null) {
            String patientKey = PATIENT_DAY_LOCK_PREFIX + dto.getPatientId() + ":" + dto.getScheduleDate();
            reg = withLock(patientKey, false, () -> withLock(SCHEDULE_LOCK_PREFIX + schedule.getId(), false,
                    () -> txTemplate.execute(status -> doCreateAppointment(dto, schedule))));
        } else {
            reg = withLock(SCHEDULE_LOCK_PREFIX + schedule.getId(), false,
                    () -> txTemplate.execute(status -> doCreateAppointment(dto, schedule)));
        }
        return enrich(List.of(reg)).get(0);
    }

    /**
     * 线上预约事务体（锁内执行）：重复检查 → 生成挂号单 → 条件抢占号源 → 占用 +1
     *
     * @param dto 预约参数
     * @param schedule 已解析排班（锁外只读快照）
     * @return 新挂号单
     */
    private Registration doCreateAppointment(AppointmentCreateDTO dto, DoctorSchedule schedule) {
        // ① AI 挂号（带患者账号）：同一患者同一天已有进行中的预约则拦截（锁内检查防重复窗口）
        if (dto.getPatientId() != null) {
            Long dupCount = registrationMapper.selectCount(new LambdaQueryWrapper<Registration>()
                    .eq(Registration::getPatientId, dto.getPatientId())
                    .eq(Registration::getScheduleDate, dto.getScheduleDate())
                    .in(Registration::getStatus,
                            RegistrationStatus.RESERVED.getCode(), RegistrationStatus.CALLED.getCode()));
            if (dupCount > 0) {
                throw new BusinessException("您在该时段已有预约，请勿重复预约");
            }
        }
        // ② 挑空闲号源并生成挂号单骨架
        Registration reg = buildRegistration(schedule, dto.getSeqNo());
        reg.setPatientName(dto.getPatientName());
        reg.setPatientPhone(dto.getPatientPhone());
        reg.setPatientIdCard(dto.getPatientIdCard());
        reg.setPatientId(dto.getPatientId());
        reg.setSourceType(SourceType.ONLINE.getCode());
        reg.setPayStatus(PayStatus.UNPAID.getCode());
        reg.setStatus(RegistrationStatus.RESERVED.getCode());
        reg.setExpireTime(LocalDateTime.now().plusMinutes(EXPIRE_MINUTES));
        // ③④ 插入挂号单并条件抢占号源
        insertWithClaim(reg, schedule.getId(), dto.getSeqNo());
        // ⑤ 占用数原子 +1
        incrRegisteredCount(schedule.getId(), 1);
        return reg;
    }

    /**
     * 患者确认支付（仅改本记录：registration 锁 + 条件更新；状态仍「已预约」仅改 pay_status）
     *
     * @param id 挂号单 ID
     * @param idCard 患者身份证（身份校验）
     * @return 挂号视图
     */
    @Override
    public RegistrationVO confirmPay(Long id, String idCard) {
        Registration reg = loadRegistration(id);
        verifyPatient(reg, idCard);
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        if (cur != RegistrationStatus.RESERVED) {
            throw new BusinessException("当前状态不可支付（仅已预约可支付）");
        }
        withLock(REGISTRATION_LOCK_PREFIX + id, false, () -> txTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            // 条件更新：status=已预约 且 pay_status=未支付 才置已支付，0 行=状态已变更
            transit(id, cur, uw -> uw
                    .eq(Registration::getPayStatus, PayStatus.UNPAID.getCode())
                    .set(Registration::getPayStatus, PayStatus.PAID.getCode())
                    .set(Registration::getPayTime, now));
            reg.setPayStatus(PayStatus.PAID.getCode());
            reg.setPayTime(now);
            reg.setUpdateTime(now);
            return reg;
        }));
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

    /**
     * 患者取消本人未取号预约（registration → schedule 锁；条件跃迁成功才释放号源/回退占用）
     *
     * @param id 挂号单 ID
     * @param idCard 患者身份证（身份校验）
     * @param reason 取消原因（可空）
     * @return 挂号视图
     */
    @Override
    public RegistrationVO patientCancel(Long id, String idCard, String reason) {
        Registration reg = loadRegistration(id);
        verifyPatient(reg, idCard);
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.CANCELLED, "患者取消预约");
        doPatientCancel(reg, cur, reason);
        return enrich(List.of(reg)).get(0);
    }

    @Override
    public List<RegistrationVO> listByPatientId(Long patientId) {
        LambdaQueryWrapper<Registration> qw = new LambdaQueryWrapper<>();
        qw.eq(Registration::getPatientId, patientId);
        qw.orderByDesc(Registration::getCreateTime);
        return enrich(registrationMapper.selectList(qw));
    }

    /**
     * AI 取消预约退号（按患者账号 ID 校验归属，规则与 patientCancel 一致）
     *
     * @param id 挂号单 ID
     * @param patientId 患者账号 ID（归属校验）
     * @param reason 取消原因（可空）
     * @return 挂号视图
     */
    @Override
    public RegistrationVO cancelByPatient(Long id, Long patientId, String reason) {
        Registration reg = loadRegistration(id);
        if (!patientId.equals(reg.getPatientId())) {
            throw new BusinessException("无权操作该预约");
        }
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.CANCELLED, "患者取消预约");
        doPatientCancel(reg, cur, reason);
        return enrich(List.of(reg)).get(0);
    }

    // ===================== 员工：现场挂号 / 取号 / 退号 / 就诊 =====================

    /**
     * 现场窗口挂号（schedule 锁 + 条件抢占号源，直接生成「已取号」单）
     *
     * @param dto 现场挂号参数
     * @return 挂号视图
     */
    @Override
    public RegistrationVO walkIn(WalkInRegisterDTO dto) {
        DoctorSchedule schedule = resolveSchedule(dto.getDoctorUserId(), dto.getScheduleDate(), dto.getPeriod());
        Registration reg = withLock(SCHEDULE_LOCK_PREFIX + schedule.getId(), false,
                () -> txTemplate.execute(status -> doWalkIn(dto, schedule)));
        return enrich(List.of(reg)).get(0);
    }

    /**
     * 现场挂号事务体（锁内执行）：生成挂号单 → 条件抢占号源 → 占用 +1
     *
     * @param dto 现场挂号参数
     * @param schedule 已解析排班（锁外只读快照）
     * @return 新挂号单
     */
    private Registration doWalkIn(WalkInRegisterDTO dto, DoctorSchedule schedule) {
        Registration reg = buildRegistration(schedule, dto.getSeqNo());
        reg.setPatientName(dto.getPatientName());
        reg.setPatientPhone(dto.getPatientPhone());
        reg.setPatientIdCard(dto.getPatientIdCard());
        reg.setSourceType(SourceType.WINDOW.getCode());
        reg.setPayStatus(PayStatus.PAID.getCode());
        if (dto.getPayAmount() != null) {
            reg.setPayAmount(dto.getPayAmount());
        }
        reg.setPayTime(LocalDateTime.now());
        // 现场挂号：直接已取号（无预约中间态）
        reg.setStatus(RegistrationStatus.CALLED.getCode());
        insertWithClaim(reg, schedule.getId(), dto.getSeqNo());
        incrRegisteredCount(schedule.getId(), 1);
        return reg;
    }

    /**
     * 取号：已预约 → 已取号（registration 锁 + 条件跃迁；未支付视为当场收费）
     *
     * @param id 挂号单 ID
     * @return 挂号视图
     */
    @Override
    public RegistrationVO take(Long id) {
        Registration reg = loadRegistration(id);
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.CALLED, "取号");
        withLock(REGISTRATION_LOCK_PREFIX + id, false, () -> txTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            boolean unpaid = PayStatus.UNPAID.getCode() == reg.getPayStatus();
            transit(id, cur, uw -> {
                uw.set(Registration::getStatus, RegistrationStatus.CALLED.getCode())
                        .set(Registration::getExpireTime, null);
                if (unpaid) {
                    // 窗口/自助机取号时若未支付，视为当场收费
                    uw.set(Registration::getPayStatus, PayStatus.PAID.getCode())
                            .set(Registration::getPayTime, now);
                }
            });
            reg.setStatus(RegistrationStatus.CALLED.getCode());
            reg.setExpireTime(null);
            if (unpaid) {
                reg.setPayStatus(PayStatus.PAID.getCode());
                reg.setPayTime(now);
            }
            reg.setUpdateTime(now);
            return reg;
        }));
        return enrich(List.of(reg)).get(0);
    }

    /**
     * 退号：已取号 → 已退号（registration → schedule 锁；条件跃迁成功才释放/回退/写退款字段）
     *
     * @param id 挂号单 ID
     * @param reason 退号原因（可空）
     * @return 挂号视图
     */
    @Override
    public RegistrationVO refund(Long id, String reason) {
        Registration reg = loadRegistration(id);
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.REFUNDED, "退号");
        withLock(REGISTRATION_LOCK_PREFIX + id, false,
                () -> withLock(SCHEDULE_LOCK_PREFIX + reg.getScheduleId(), false,
                        () -> txTemplate.execute(status -> {
                            LocalDateTime now = LocalDateTime.now();
                            String remark = appendReason(reg.getRemark(), "退号：" + (reason == null ? "" : reason));
                            transit(id, cur, uw -> uw
                                    .set(Registration::getStatus, RegistrationStatus.REFUNDED.getCode())
                                    .set(Registration::getRefundAmount, reg.getPayAmount())
                                    .set(Registration::getRefundTime, now)
                                    .set(Registration::getRemark, remark));
                            // 条件跃迁成功后：释放号源 + 占用数回退
                            releaseSource(reg.getNumberSourceId(), id);
                            incrRegisteredCount(reg.getScheduleId(), -1);
                            reg.setStatus(RegistrationStatus.REFUNDED.getCode());
                            reg.setRefundAmount(reg.getPayAmount());
                            reg.setRefundTime(now);
                            reg.setRemark(remark);
                            reg.setUpdateTime(now);
                            return reg;
                        })));
        return enrich(List.of(reg)).get(0);
    }

    /**
     * 标记就诊：已取号 → 已就诊（registration 锁 + 条件跃迁，医生仅能操作本人患者）
     *
     * @param id 挂号单 ID
     * @return 挂号视图
     */
    @Override
    public RegistrationVO treat(Long id) {
        Registration reg = loadRegistration(id);
        assertReadable(reg); // 医生仅能操作本人患者
        RegistrationStatus cur = RegistrationStatus.of(reg.getStatus());
        cur.assertTransit(RegistrationStatus.TREATED, "标记就诊");
        withLock(REGISTRATION_LOCK_PREFIX + id, false, () -> txTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            transit(id, cur, uw -> uw
                    .set(Registration::getStatus, RegistrationStatus.TREATED.getCode())
                    .set(Registration::getTreatTime, now));
            reg.setStatus(RegistrationStatus.TREATED.getCode());
            reg.setTreatTime(now);
            reg.setUpdateTime(now);
            return reg;
        }));
        return enrich(List.of(reg)).get(0);
    }

    // ===================== 定时任务 =====================

    /**
     * 单条过期预约处理（定时任务逐条调用；fail-fast 拿不到锁直接跳过，下轮重试）
     *
     * @param id 挂号单 ID
     * @return true=已置过期失效并释放号源；false=跳过（锁被占/状态已变/不再满足过期条件）
     */
    @Override
    public boolean expireOverdueOne(Long id) {
        Registration pre = registrationMapper.selectById(id);
        if (pre == null || pre.getScheduleId() == null) {
            return false;
        }
        try {
            Boolean expired = withLock(REGISTRATION_LOCK_PREFIX + id, true,
                    () -> withLock(SCHEDULE_LOCK_PREFIX + pre.getScheduleId(), true,
                            () -> txTemplate.execute(status -> doExpireOverdue(id))));
            return Boolean.TRUE.equals(expired);
        } catch (BusinessException e) {
            // 锁被占用或状态已变：跳过本条，由下个周期重试
            log.debug("过期预约跳过：id={}, reason={}", id, e.getMessage());
            return false;
        }
    }

    /**
     * 过期处理事务体（锁内重读最新状态，条件跃迁 RESERVED→EXPIRED 成功才释放/回退）
     *
     * @param id 挂号单 ID
     * @return true=处理成功；false=不再满足过期条件
     */
    private boolean doExpireOverdue(Long id) {
        Registration reg = registrationMapper.selectById(id);
        LocalDateTime now = LocalDateTime.now();
        if (reg == null || reg.getStatus() == null
                || reg.getStatus() != RegistrationStatus.RESERVED.getCode()
                || reg.getExpireTime() == null || !reg.getExpireTime().isBefore(now)) {
            return false;
        }
        transit(id, RegistrationStatus.RESERVED, uw -> uw
                .set(Registration::getStatus, RegistrationStatus.EXPIRED.getCode()));
        releaseSource(reg.getNumberSourceId(), id);
        incrRegisteredCount(reg.getScheduleId(), -1);
        return true;
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
                String name = orgNames(List.of(dimId), orgClient::departments).get(dimId);
                return name == null ? "未知科室" : name;
            }
            if ("status".equals(dimension)) {
                return RegistrationStatus.of(dimId.intValue()).getDesc();
            }
            if (dimId == -1L) {
                return "未知";
            }
            String name = doctorNames(List.of(dimId)).get(dimId);
            return name == null ? "未知医生" : name;
        });
    }

    // ===================== 内部辅助：锁与条件更新 =====================

    /**
     * 执行受 Redisson 分布式锁保护的逻辑（先锁 → 事务提交 → 解锁；
     * 锁必须覆盖事务提交，故用 TransactionTemplate 包事务，禁止改回方法级 @Transactional）
     *
     * @param key 锁键（全局固定获取顺序：patient → registration → schedule）
     * @param failFast true=定时任务模式 tryLock(0) 拿不到立即失败；false=常规等待 LOCK_WAIT_SECONDS 秒
     * @param body 锁内逻辑
     * @param <T> 返回类型
     * @return 锁内逻辑返回值
     */
    private <T> T withLock(String key, boolean failFast, Supplier<T> body) {
        RLock lock = redissonClient.getLock(key);
        boolean acquired;
        try {
            acquired = failFast
                    ? lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)
                    : lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("当前操作繁忙，请稍后重试");
        }
        if (!acquired) {
            throw new BusinessException("当前操作繁忙，请稍后重试");
        }
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 状态条件跃迁（锁内兜底）：仅当当前状态仍为 from 才更新，0 行说明状态已被并发变更
     *
     * @param id 挂号单 ID
     * @param from 期望的当前状态（前置校验时的快照）
     * @param extra 追加 set/where 条件（目标状态与业务字段）
     */
    private void transit(Long id, RegistrationStatus from, Consumer<LambdaUpdateWrapper<Registration>> extra) {
        LambdaUpdateWrapper<Registration> uw = new LambdaUpdateWrapper<Registration>()
                .eq(Registration::getId, id)
                .eq(Registration::getStatus, from.getCode())
                .set(Registration::getUpdateTime, LocalDateTime.now());
        extra.accept(uw);
        if (registrationMapper.update(null, uw) == 0) {
            throw new BusinessException("状态已变更，请刷新后重试");
        }
    }

    /**
     * 条件释放号源：仅当号源已占用(status=2)且归属本单(registration_id=本单)才置回空闲，
     * 防误释放并发单占用中的号源（无行锁）
     *
     * @param numberSourceId 号源 ID（可空）
     * @param registrationId 本单挂号 ID
     */
    private void releaseSource(Long numberSourceId, Long registrationId) {
        if (numberSourceId == null) return;
        int rows = numberSourceMapper.update(null, new LambdaUpdateWrapper<NumberSource>()
                .eq(NumberSource::getId, numberSourceId)
                .eq(NumberSource::getStatus, SOURCE_OCCUPIED)
                .eq(NumberSource::getRegistrationId, registrationId)
                .set(NumberSource::getStatus, SOURCE_FREE)
                .set(NumberSource::getRegistrationId, null));
        if (rows == 0) {
            log.warn("号源释放未命中（已释放或归属不符）：numberSourceId={}, registrationId={}",
                    numberSourceId, registrationId);
        }
    }

    /**
     * 排班占用数原子增减（SQL 自增/自减防读-改-写丢失更新；delta 为内部常量 ±1，减时防负）
     *
     * @param scheduleId 排班 ID（可空）
     * @param delta +1 或 -1
     */
    private void incrRegisteredCount(Long scheduleId, int delta) {
        if (scheduleId == null) return;
        LambdaUpdateWrapper<DoctorSchedule> uw = new LambdaUpdateWrapper<DoctorSchedule>()
                .eq(DoctorSchedule::getId, scheduleId);
        if (delta > 0) {
            uw.setSql("registered_count = registered_count + 1");
        } else {
            uw.setSql("registered_count = registered_count - 1")
                    .gt(DoctorSchedule::getRegisteredCount, 0);
        }
        scheduleMapper.update(null, uw);
    }

    /**
     * 患者取消预约（锁内条件跃迁成功后再副作用：释放号源 + 占用回退；全程无行锁）
     *
     * @param reg 挂号记录（调用方已完成身份/归属校验与前置状态校验）
     * @param from 前置校验时的当前状态（用于条件跃迁兜底）
     * @param reason 取消原因（可空）
     */
    private void doPatientCancel(Registration reg, RegistrationStatus from, String reason) {
        withLock(REGISTRATION_LOCK_PREFIX + reg.getId(), false,
                () -> withLock(SCHEDULE_LOCK_PREFIX + reg.getScheduleId(), false,
                        () -> txTemplate.execute(status -> {
                            String remark = appendReason(reg.getRemark(), "患者取消：" + (reason == null ? "" : reason));
                            transit(reg.getId(), from, uw -> uw
                                    .set(Registration::getStatus, RegistrationStatus.CANCELLED.getCode())
                                    .set(Registration::getRemark, remark));
                            // 条件跃迁成功后：释放号源 + 占用数回退
                            releaseSource(reg.getNumberSourceId(), reg.getId());
                            incrRegisteredCount(reg.getScheduleId(), -1);
                            reg.setStatus(RegistrationStatus.CANCELLED.getCode());
                            reg.setRemark(remark);
                            reg.setUpdateTime(LocalDateTime.now());
                            return reg;
                        })));
    }

    // ===================== 内部辅助：号源与挂号单 =====================

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

    /**
     * 挑空闲号源并生成挂号单骨架（排班快照与号源指向，业务字段由调用方填充）
     *
     * @param schedule 排班
     * @param seqNo 指定序号（可空）
     * @return 挂号单骨架
     */
    private Registration buildRegistration(DoctorSchedule schedule, Integer seqNo) {
        NumberSource source = pickFreeSource(schedule.getId(), seqNo);
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
        reg.setPayAmount(DEFAULT_FEE);
        reg.setCreateTime(LocalDateTime.now());
        reg.setUpdateTime(LocalDateTime.now());
        return reg;
    }

    /**
     * 插入挂号单并以条件更新抢占号源，0 行说明候选号源被并发抢走，
     * 重挑空闲号源修正指向后重试，至多 CLAIM_RETRY_TIMES 次，仍失败抛业务异常
     *
     * @param reg 已填好业务字段的挂号单（numberSourceId 指向当前候选号源）
     * @param scheduleId 排班 ID（重挑范围）
     * @param seqNo 指定序号（可空）
     */
    private void insertWithClaim(Registration reg, Long scheduleId, Integer seqNo) {
        registrationMapper.insert(reg);
        int retry = 0;
        while (!tryClaimSource(reg.getNumberSourceId(), reg.getId())) {
            if (retry++ >= CLAIM_RETRY_TIMES) {
                throw new BusinessException("号源已满或该序号不可用");
            }
            NumberSource next = pickFreeSource(scheduleId, seqNo);
            reg.setNumberSourceId(next.getId());
            reg.setSeqNo(next.getSeqNo());
            reg.setRegistrationNo(genRegistrationNo(scheduleId, next.getSeqNo()));
            registrationMapper.update(null, new LambdaUpdateWrapper<Registration>()
                    .eq(Registration::getId, reg.getId())
                    .set(Registration::getNumberSourceId, next.getId())
                    .set(Registration::getSeqNo, next.getSeqNo())
                    .set(Registration::getRegistrationNo, reg.getRegistrationNo()));
        }
    }

    /**
     * 条件抢占号源：仅当号源仍空闲(status=1)才置占用并挂本单 ID（无行锁）
     *
     * @param numberSourceId 号源 ID
     * @param registrationId 挂号单 ID
     * @return true=抢占成功；false=0 行（已被并发抢占）
     */
    private boolean tryClaimSource(Long numberSourceId, Long registrationId) {
        return numberSourceMapper.update(null, new LambdaUpdateWrapper<NumberSource>()
                .eq(NumberSource::getId, numberSourceId)
                .eq(NumberSource::getStatus, SOURCE_FREE)
                .set(NumberSource::getStatus, SOURCE_OCCUPIED)
                .set(NumberSource::getRegistrationId, registrationId)) > 0;
    }

    private NumberSource pickFreeSource(Long scheduleId, Integer seqNo) {
        LambdaQueryWrapper<NumberSource> qw = new LambdaQueryWrapper<>();
        qw.eq(NumberSource::getScheduleId, scheduleId).eq(NumberSource::getStatus, SOURCE_FREE);
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
        Map<Long, String> hNames = orgNames(
                list.stream().map(Registration::getHospitalId).toList(), orgClient::hospitals);
        Map<Long, String> dNames = orgNames(
                list.stream().map(Registration::getDepartmentId).toList(), orgClient::departments);
        Map<Long, String> uNames = doctorNames(
                list.stream().map(Registration::getDoctorUserId).toList());
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
