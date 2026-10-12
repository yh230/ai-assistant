package com.aiassistant.hospital.dto;

import lombok.Data;

/**
 * 医院员工查询条件（分页）。
 * <p>
 * 数据范围由后端按登录人自动过滤：院长/管理员看全部，科室主任仅本科室；
 * 前端传入的 hospitalId/departmentId 仅作院长维度的可选二次筛选。
 */
@Data
public class EmployeeQueryDTO {

    private String keyword;

    private Long hospitalId;

    private Long departmentId;

    private long pageNum = 1;

    private long pageSize = 10;
}
