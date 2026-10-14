package com.hospital.service;

import com.common.result.PageResult;
import com.hospital.dto.EmployeeQueryDTO;
import com.hospital.dto.EmployeeSaveDTO;
import com.hospital.dto.EmployeeUpdateDTO;
import com.hospital.vo.EmployeeVO;

/**
 * 医院员工服务（底层维护 sys_user，绑定医院/科室/岗位角色）。
 * <p>
 * 列表按数据权限过滤：院长/管理员看全部，科室主任仅本科室；写操作做范围校验。
 */
public interface EmployeeService {

    PageResult<EmployeeVO> pageEmployees(EmployeeQueryDTO query);

    EmployeeVO get(Long id);

    void create(EmployeeSaveDTO dto);

    void update(Long id, EmployeeUpdateDTO dto);

    void delete(Long id);

    void changeStatus(Long id, Integer status);

    void resetPassword(Long id, String newPassword);
}
