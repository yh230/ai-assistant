package com.registration.controller;

import com.common.result.Result;
import com.registration.service.NumberSourceService;
import com.registration.vo.NumberSourceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 号源池查询接口（号源余量、号位明细，本院数据）
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/registration/source")
@RequiredArgsConstructor
public class NumberSourceController {

    private final NumberSourceService numberSourceService;

    @GetMapping("/pools")
    @PreAuthorize("hasAuthority('registration:schedule:manage')")
    public Result<List<NumberSourceVO>> pools(@RequestParam(required = false) Long doctorUserId,
                                               @RequestParam(required = false) Long departmentId,
                                               @RequestParam(required = false) String date,
                                               @RequestParam(required = false) Integer period) {
        LocalDate d = (date != null && !date.isBlank()) ? LocalDate.parse(date) : null;
        return Result.success(numberSourceService.listPools(null, doctorUserId, departmentId, d, period));
    }

    @GetMapping("/detail")
    @PreAuthorize("hasAuthority('registration:schedule:manage')")
    public Result<NumberSourceVO> detail(@RequestParam Long scheduleId) {
        return Result.success(numberSourceService.detail(scheduleId));
    }
}
