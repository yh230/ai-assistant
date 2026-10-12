package com.aiassistant.registration.controller;

import com.aiassistant.common.result.PageResult;
import com.aiassistant.common.result.Result;
import com.aiassistant.registration.dto.ScheduleQueryDTO;
import com.aiassistant.registration.dto.ScheduleSaveDTO;
import com.aiassistant.registration.service.DoctorScheduleService;
import com.aiassistant.registration.vo.ScheduleVO;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 医生出诊排班 / 号源配置接口（门诊管理员，本院数据）
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/registration/schedule")
@RequiredArgsConstructor
public class DoctorScheduleController {

    private final DoctorScheduleService scheduleService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('registration:schedule:manage')")
    public Result<PageResult<ScheduleVO>> page(@RequestParam(required = false) Long doctorUserId,
                                               @RequestParam(required = false) Long departmentId,
                                               @RequestParam(required = false) Long hospitalId,
                                               @RequestParam(required = false) Integer period,
                                               @RequestParam(required = false) String dateFrom,
                                               @RequestParam(required = false) String dateTo,
                                               @RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize) {
        ScheduleQueryDTO q = new ScheduleQueryDTO();
        q.setDoctorUserId(doctorUserId);
        q.setDepartmentId(departmentId);
        q.setHospitalId(hospitalId);
        q.setPeriod(period);
        q.setPageNum(pageNum);
        q.setPageSize(pageSize);
        if (dateFrom != null && !dateFrom.isBlank()) q.setDateFrom(LocalDate.parse(dateFrom));
        if (dateTo != null && !dateTo.isBlank()) q.setDateTo(LocalDate.parse(dateTo));
        return Result.success(scheduleService.page(q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('registration:schedule:manage')")
    public Result<ScheduleVO> get(@PathVariable Long id) {
        return Result.success(scheduleService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('registration:schedule:manage')")
    public Result<ScheduleVO> save(@RequestBody ScheduleSaveDTO dto) {
        return Result.success(scheduleService.save(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('registration:schedule:manage')")
    public Result<ScheduleVO> update(@PathVariable Long id, @RequestBody ScheduleSaveDTO dto) {
        return Result.success(scheduleService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('registration:schedule:manage')")
    public Result<Void> remove(@PathVariable Long id) {
        scheduleService.remove(id);
        return Result.success();
    }

    @PostMapping("/{id}/generate")
    @PreAuthorize("hasAuthority('registration:schedule:manage')")
    public Result<Void> generate(@PathVariable Long id) {
        scheduleService.generateSources(id);
        return Result.success();
    }

    /** 公开：患者预约选号用，返回有出诊排班的医生列表（只读启用中排班） */
    @GetMapping("/public/schedules")
    public Result<List<com.aiassistant.registration.vo.ScheduleVO>> publicSchedules(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Long departmentId) {
        java.time.LocalDate d = (date != null && !date.isBlank()) ? java.time.LocalDate.parse(date) : null;
        return Result.success(scheduleService.listPublic(d, departmentId));
    }
}
