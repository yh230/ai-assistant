package com.registration.job;

import com.registration.entity.Registration;
import com.registration.enums.RegistrationStatus;
import com.registration.mapper.RegistrationMapper;
import com.registration.service.RegistrationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预约超时释放定时任务：每分钟扫描「已预约(锁定)」且超过 expire_time 未支付/未取号的记录，
 * 逐条经服务层锁 + 事务处理，标记「过期失效」并释放号源（号源回收）；
 * 锁冲突或状态已变则跳过，下个周期重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentExpireScheduler {

    private final RegistrationMapper registrationMapper;
    private final RegistrationService registrationService;

    @Scheduled(fixedDelay = 60000)
    public void expireOverdue() {
        LocalDateTime now = LocalDateTime.now();
        List<Registration> overdue = registrationMapper.selectList(new LambdaQueryWrapper<Registration>()
                .select(Registration::getId)
                .eq(Registration::getStatus, RegistrationStatus.RESERVED.getCode())
                .lt(Registration::getExpireTime, now)
                // 单轮最多处理 200 条，避免积压时一次拉全量；剩余由后续周期继续处理
                .last("LIMIT 200"));
        int released = 0;
        for (Registration reg : overdue) {
            if (registrationService.expireOverdueOne(reg.getId())) {
                released++;
            }
        }
        if (released > 0) {
            log.info("过期预约释放 {} 条", released);
        }
    }
}
