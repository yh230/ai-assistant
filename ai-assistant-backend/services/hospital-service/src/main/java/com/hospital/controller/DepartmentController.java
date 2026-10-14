package com.hospital.controller;

import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.operlog.OperLog;
import com.hospital.dto.DepartmentSaveDTO;
import com.hospital.service.DepartmentService;
import com.hospital.vo.DepartmentVO;
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
 * 科室管理接口（组织基础数据，仅院长/管理员可维护；列表按数据权限过滤）
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/hospital/department")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<PageResult<DepartmentVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "10") long pageSize,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Long hospitalId) {
        return Result.success(departmentService.pageDepartments(keyword, hospitalId, pageNum, pageSize));
    }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<List<DepartmentVO>> list(@RequestParam(required = false) Long hospitalId) {
        return Result.success(departmentService.listByHospital(hospitalId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<DepartmentVO> get(@PathVariable Long id) {
        return Result.success(departmentService.get(id));
    }

    @OperLog(module = "科室管理", action = "新增科室")
    @PostMapping
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<Void> create(@Valid @RequestBody DepartmentSaveDTO dto) {
        departmentService.create(dto);
        return Result.success();
    }

    @OperLog(module = "科室管理", action = "修改科室")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DepartmentSaveDTO dto) {
        departmentService.update(id, dto);
        return Result.success();
    }

    @OperLog(module = "科室管理", action = "删除科室")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        departmentService.delete(id);
        return Result.success();
    }

    @OperLog(module = "科室管理", action = "科室启用禁用")
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('hospital:manage')")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        departmentService.changeStatus(id, status);
        return Result.success();
    }
}
