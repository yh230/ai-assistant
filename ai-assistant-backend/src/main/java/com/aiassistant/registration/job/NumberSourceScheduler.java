package com.aiassistant.registration.job;

import com.aiassistant.registration.entity.DoctorSchedule;
import com.aiassistant.registration.mapper.DoctorScheduleMapper;
import com.aiassistant.registration.service.NumberSourceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 号源生成定时任务：每天凌晨为「启用中」且出诊日期 >= 今天的排班预生成号源。
 * <p>需求：基于医生排班定时生成每日号源（上午/下午，设置号源总数）。
 * 已生成的空闲号源会被清理后按 total_limit 重新补齐，占用中的号源（归属既有挂号）保留。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NumberSourceScheduler {

    private final DoctorScheduleMapper scheduleMapper;
    private final NumberSourceService numberSourceService;

    @Scheduled(cron = "0 0 1 * * ?")
    public void generateDailySources() {
        LocalDate today = LocalDate.now();
        List<DoctorSchedule> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<DoctorSchedule>()
                .eq(DoctorSchedule::getStatus, 1)
                .ge(DoctorSchedule::getScheduleDate, today));
        int total = 0;
        for (DoctorSchedule s : schedules) {
            try {
                numberSourceService.generateForSchedule(s);
                total++;
            } catch (Exception e) {
                log.warn("号源生成失败 scheduleId={}: {}", s.getId(), e.getMessage());
            }
        }
        log.info("号源预生成完成，处理排班 {} 条", total);
    }
}
