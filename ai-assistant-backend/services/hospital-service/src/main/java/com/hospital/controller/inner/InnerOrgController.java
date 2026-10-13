package com.hospital.controller.inner;

import com.api.dto.OrgBriefDTO;
import com.common.result.Result;
import com.hospital.service.DepartmentService;
import com.hospital.service.HospitalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组织主数据内部接口（仅供服务间 Feign 调用，网关阻断外部访问）
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/inner")
@RequiredArgsConstructor
public class InnerOrgController {

    private final HospitalService hospitalService;
    private final DepartmentService departmentService;

    /** 按 ID 批量查询医院简要信息 */
    @GetMapping("/hospital/batch")
    public Result<List<OrgBriefDTO>> hospitals(@RequestParam("ids") List<Long> ids) {
        return Result.success(hospitalService.batchBrief(ids));
    }

    /** 按 ID 批量查询科室简要信息 */
    @GetMapping("/department/batch")
    public Result<List<OrgBriefDTO>> departments(@RequestParam("ids") List<Long> ids) {
        return Result.success(departmentService.batchBrief(ids));
    }

    /** 科室下拉（可按医院过滤） */
    @GetMapping("/department/list")
    public Result<List<OrgBriefDTO>> departmentList(@RequestParam(value = "hospitalId", required = false) Long hospitalId) {
        return Result.success(departmentService.listBrief(hospitalId));
    }
}
