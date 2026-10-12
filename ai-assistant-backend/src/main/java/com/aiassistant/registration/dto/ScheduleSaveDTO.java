package com.aiassistant.registration.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 排班/号源配置保存（门诊管理员）。医院/科室由 doctorUserId 推导，前端不传。
 */
@Data
public class ScheduleSaveDTO {
    /** 更新时传 */
    private Long id;
    /** 医生用户ID（关联 sys_user，必须绑定科室/医院） */
    private Long doctorUserId;
    private LocalDate scheduleDate;
    /** 时段 1=上午 2=下午 */
    private Integer period;
    /** 号源总数 */
    private Integer totalLimit;
    private String remark;
}
