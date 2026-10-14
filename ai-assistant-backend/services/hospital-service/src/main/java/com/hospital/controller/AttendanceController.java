package com.hospital.controller;

import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.operlog.OperLog;
import com.hospital.dto.AttendanceQueryDTO;
import com.hospital.dto.AttendanceSaveDTO;
import com.hospital.service.AttendanceService;
import com.hospital.vo.AttendanceVO;
import lombok.RequiredArgsConstructor;
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

import java.util.Map;

/**
 * 出勤管理接口（核心数据权限模块）
 * <p>
 * 读接口权限：hospital:attendance:list 或 hospital:attendance:self（普通员工仅本人）。
 * 写接口权限：hospital:attendance:edit。所有读/写均按登录人角色+科室自动过滤与校验。
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/hospital/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    @GetMapping("/page")
    @PreAuthorize("hasAnyAuthority('hospital:attendance:list','hospital:attendance:self')")
    public Result<PageResult<AttendanceVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "10") long pageSize,
                                                 @RequestParam(required = false) Long hospitalId,
                                                 @RequestParam(required = false) Long departmentId,
                                                 @RequestParam(required = false) String dateStart,
                                                 @RequestParam(required = false) String dateEnd,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(required = false) String keyword) {
        AttendanceQueryDTO q = new AttendanceQueryDTO();
        q.setPageNum(pageNum);
        q.setPageSize(pageSize);
        q.setHospitalId(hospitalId);
        q.setDepartmentId(departmentId);
        q.setStatus(status);
        q.setKeyword(keyword);
        if (dateStart != null && !dateStart.isBlank()) {
            q.setDateStart(java.time.LocalDate.parse(dateStart));
        }
        if (dateEnd != null && !dateEnd.isBlank()) {
            q.setDateEnd(java.time.LocalDate.parse(dateEnd));
        }
        return Result.success(attendanceService.pageAttendance(q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('hospital:attendance:list','hospital:attendance:self')")
    public Result<AttendanceVO> get(@PathVariable Long id) {
        return Result.success(attendanceService.get(id));
    }

    @OperLog(module = "出勤管理", action = "新增出勤")
    @PostMapping
    @PreAuthorize("hasAuthority('hospital:attendance:edit')")
    public Result<Void> create(@RequestBody AttendanceSaveDTO dto) {
        attendanceService.create(dto);
        return Result.success();
    }

    @OperLog(module = "出勤管理", action = "修改出勤")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:attendance:edit')")
    public Result<Void> update(@PathVariable Long id, @RequestBody AttendanceSaveDTO dto) {
        attendanceService.update(id, dto);
        return Result.success();
    }

    @OperLog(module = "出勤管理", action = "删除出勤")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:attendance:edit')")
    public Result<Void> delete(@PathVariable Long id) {
        attendanceService.delete(id);
        return Result.success();
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('hospital:stats:view')")
    public Result<Map<String, Object>> stats(@RequestParam(defaultValue = "department") String dimension) {
        return Result.success(attendanceService.stats(dimension));
    }
}
