package com.registration.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 挂号/预约/取号记录视图（含状态文本与人员名称）。
 */
@Data
public class RegistrationVO {
    private Long id;
    /** 挂号凭证号 */
    private String registrationNo;
    private Long hospitalId;
    private String hospitalName;
    private Long departmentId;
    private String departmentName;
    private Long doctorUserId;
    private String doctorName;
    private Long scheduleId;
    private Long numberSourceId;
    private java.time.LocalDate scheduleDate;
    private Integer period;
    private String periodText;
    /** 就诊序号 */
    private Integer seqNo;

    private String patientName;
    private String patientPhone;
    /** 身份证脱敏展示 */
    private String patientIdCard;
    /** 1=线上预约 2=现场窗口 3=自助机 */
    private Integer sourceType;
    private String sourceTypeText;
    /** 0=未支付 1=已支付 2=超时作废 */
    private Integer payStatus;
    private String payStatusText;
    private BigDecimal payAmount;
    private LocalDateTime payTime;

    /** 状态机：1=已预约 2=已取号 3=已就诊 4=已取消 5=已退号 6=过期失效 */
    private Integer status;
    private String statusText;
    private LocalDateTime expireTime;
    private LocalDateTime treatTime;
    private BigDecimal refundAmount;
    private LocalDateTime refundTime;

    private String remark;
    private LocalDateTime createTime;
}
