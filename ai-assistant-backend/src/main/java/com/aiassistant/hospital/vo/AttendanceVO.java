package com.aiassistant.hospital.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 出勤视图对象（含员工姓名、医院/科室名称、状态文案）。
 */
@Data
public class AttendanceVO {

    private Long id;
    private Long userId;
    private String userName;
    private Long hospitalId;
    private String hospitalName;
    private Long departmentId;
    private String departmentName;
    private LocalDate attendDate;
    private Integer status;
    private String statusText;
    private String checkIn;
    private String checkOut;
    private String remark;
    private LocalDateTime createTime;
}
