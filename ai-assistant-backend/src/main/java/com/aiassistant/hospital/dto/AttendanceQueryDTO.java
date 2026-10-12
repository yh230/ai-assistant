package com.aiassistant.hospital.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 出勤查询条件（分页）
 * <p>
 * 数据范围由后端 {@link com.aiassistant.common.security.DataScopeResolver} 按登录人自动过滤，
 * 前端不传权限参数；hospitalId/departmentId 仅作为院长维度的可选二次筛选。
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
public class AttendanceQueryDTO {

    private Long hospitalId;

    private Long departmentId;

    private LocalDate dateStart;

    private LocalDate dateEnd;

    /** 出勤状态（可选） */
    private Integer status;

    /** 姓名/账号关键字（可选） */
    private String keyword;

    private long pageNum = 1;

    private long pageSize = 10;
}
