package com.hospital.controller;

import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.operlog.OperLog;
import com.hospital.dto.HospitalSaveDTO;
import com.hospital.service.HospitalService;
import com.hospital.vo.HospitalVO;
import jakarta.validation.Valid;
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

import java.util.List;

/**
 * 医院管理接口（组织基础数据，仅院长/管理员可维护）
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/hospital/info")
@RequiredArgsConstructor
public class HospitalController {

    private final HospitalService hospitalService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<PageResult<HospitalVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String keyword) {
        return Result.success(hospitalService.pageHospitals(keyword, pageNum, pageSize));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<List<HospitalVO>> all() {
        return Result.success(hospitalService.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<HospitalVO> get(@PathVariable Long id) {
        return Result.success(hospitalService.get(id));
    }

    @OperLog(module = "医院管理", action = "新增医院")
    @PostMapping
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<Void> create(@Valid @RequestBody HospitalSaveDTO dto) {
        hospitalService.create(dto);
        return Result.success();
    }

    @OperLog(module = "医院管理", action = "修改医院")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody HospitalSaveDTO dto) {
        hospitalService.update(id, dto);
        return Result.success();
    }

    @OperLog(module = "医院管理", action = "删除医院")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        hospitalService.delete(id);
        return Result.success();
    }

    @OperLog(module = "医院管理", action = "医院启用禁用")
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        hospitalService.changeStatus(id, status);
        return Result.success();
    }
}
