package com.registration.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 现场窗口挂号（收费员）。创建即"已取号"，直接生成挂号单，无需预约中间态。
 */
@Data
public class WalkInRegisterDTO {
    private Long doctorUserId;
    private LocalDate scheduleDate;
    private Integer period;
    /** 可选：指定就诊序号；不传自动分配首个空闲号源 */
    private Integer seqNo;
    private String patientName;
    private String patientPhone;
    private String patientIdCard;
    /** 收费金额 */
    private BigDecimal payAmount;
}
