package com.aiassistant.registration.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 挂号记录筛选（后端按数据权限自动过滤，前端零权限参数）。
 */
@Data
public class RegistrationQueryDTO {
    private Long doctorUserId;
    private Long departmentId;
    private Long hospitalId;
    private String patientPhone;
    private String patientName;
    private Integer status;
    private Integer period;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
