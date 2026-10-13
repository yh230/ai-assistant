package com.registration.job;

import com.common.exception.BusinessException;
import com.registration.entity.DoctorSchedule;
import com.registration.entity.NumberSource;
import com.registration.entity.Registration;
import com.registration.enums.RegistrationStatus;
import com.registration.mapper.NumberSourceMapper;
import com.registration.mapper.RegistrationMapper;
import com.registration.mapper.DoctorScheduleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预约超时释放定时任务：每分钟扫描「已预约(锁定)」且超过 expire_time 未支付/未取号的记录，
 * 标记「过期失效」并释放号源（号源回收）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentExpireScheduler {

    private final RegistrationMapper registrationMapper;
    private final NumberSourceMapper numberSourceMapper;
    private final DoctorScheduleMapper scheduleMapper;

    @Scheduled(fixedDelay = 60000)
    @Transactional(rollbackFor = Exception.class)
    public void expireOverdue() {
        LocalDateTime now = LocalDateTime.now();
        List<Registration> overdue = registrationMapper.selectList(new LambdaQueryWrapper<Registration>()
                .eq(Registration::getStatus, RegistrationStatus.RESERVED.getCode())
                .lt(Registration::getExpireTime, now));
        int released = 0;
        for (Registration reg : overdue) {
            // 标记过期失效
            reg.setStatus(RegistrationStatus.EXPIRED.getCode());
            reg.setUpdateTime(now);
            registrationMapper.updateById(reg);
            // 释放号源
            if (reg.getNumberSourceId() != null) {
                NumberSource src = numberSourceMapper.selectById(reg.getNumberSourceId());
                if (src != null && src.getStatus() == 2) {
                    src.setStatus(1);
                    src.setRegistrationId(null);
                    numberSourceMapper.updateById(src);
                }
            }
            // 占用数回退
            if (reg.getScheduleId() != null) {
                DoctorSchedule s = scheduleMapper.selectById(reg.getScheduleId());
                if (s != null && s.getRegisteredCount() != null && s.getRegisteredCount() > 0) {
                    s.setRegisteredCount(s.getRegisteredCount() - 1);
                    scheduleMapper.updateById(s);
                }
            }
            released++;
        }
        if (released > 0) {
            log.info("过期预约释放 {} 条", released);
        }
    }
}
