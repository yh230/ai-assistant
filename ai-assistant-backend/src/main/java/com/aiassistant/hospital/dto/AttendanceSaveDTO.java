package com.aiassistant.hospital.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 出勤新增/编辑请求。
 * <p>
 * hospital_id / department_id 不接收前端传入，由后端按员工的 sys_user 归属自动填充，
 * 防止越权写错科室。
 */
@Data
public class AttendanceSaveDTO {

    @NotNull(message = "员工不能为空")
    private Long userId;

    @NotNull(message = "出勤日期不能为空")
    private LocalDate attendDate;

    /** 出勤状态：1-出勤 2-缺勤 3-请假 4-迟到 */
    private Integer status = 1;

    /** 上班打卡 HH:mm */
    private String checkIn;

    /** 下班打卡 HH:mm */
    private String checkOut;

    private String remark;
}
