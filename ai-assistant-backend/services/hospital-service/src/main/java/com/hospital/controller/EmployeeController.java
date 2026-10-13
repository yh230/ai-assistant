package com.hospital.controller;

import com.api.client.UserClient;
import com.api.dto.ResetPasswordDTO;
import com.api.dto.RoleBriefDTO;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.common.result.Result;
import com.hospital.dto.EmployeeQueryDTO;
import com.hospital.dto.EmployeeSaveDTO;
import com.hospital.dto.EmployeeUpdateDTO;
import com.hospital.service.EmployeeService;
import com.hospital.vo.EmployeeVO;
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
 * 医院员工管理接口（底层经用户中心 Feign 维护 sys_user，绑定医院/科室/岗位角色）
 * <p>
 * 列表按数据权限过滤（院长/管理员全部，科室主任仅本科室）；写操作需 hospital:employee:edit 且受范围校验
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/hospital/employee")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final UserClient userClient;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('hospital:employee:list')")
    public Result<PageResult<EmployeeVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long hospitalId,
                                               @RequestParam(required = false) Long departmentId) {
        EmployeeQueryDTO q = new EmployeeQueryDTO();
        q.setKeyword(keyword);
        q.setHospitalId(hospitalId);
        q.setDepartmentId(departmentId);
        q.setPageNum(pageNum);
        q.setPageSize(pageSize);
        return Result.success(employeeService.pageEmployees(q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:employee:list')")
    public Result<EmployeeVO> get(@PathVariable Long id) {
        return Result.success(employeeService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('hospital:employee:edit')")
    public Result<Void> create(@RequestBody EmployeeSaveDTO dto) {
        employeeService.create(dto);
        return Result.success();
    }

    /**
     * 员工表单的角色下拉（医院域，无需 system:role:list）。
     * 院长/科室主任均可调用，避免暴露系统角色管理权限。
     */
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('hospital:employee:list')")
    public Result<List<RoleBriefDTO>> roles() {
        Result<List<RoleBriefDTO>> result = userClient.roles();
        if (result == null || result.getCode() != 200) {
            throw new BusinessException(result == null ? "查询角色列表失败" : result.getMsg());
        }
        return Result.success(result.getData());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:employee:edit')")
    public Result<Void> update(@PathVariable Long id, @RequestBody EmployeeUpdateDTO dto) {
        employeeService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('hospital:employee:edit')")
    public Result<Void> delete(@PathVariable Long id) {
        employeeService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('hospital:employee:edit')")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        employeeService.changeStatus(id, status);
        return Result.success();
    }

    /**
     * 重置指定员工密码
     * <p>
     * 请求体为空时按「使用默认密码重置」处理
     *
     * @param id 员工 ID
     * @param dto 重置密码请求体（可空）
     * @return 操作结果
     */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('hospital:employee:edit')")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @RequestBody(required = false) ResetPasswordDTO dto) {
        employeeService.resetPassword(id, dto == null ? null : dto.getNewPassword());
        return Result.success();
    }
}
