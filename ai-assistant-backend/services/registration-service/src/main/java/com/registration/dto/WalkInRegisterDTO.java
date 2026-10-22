package com.registration.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 现场窗口挂号（收费员）。创建即"已取号"，直接生成挂号单，无需预约中间态。
 */
@Data
public class WalkInRegisterDTO {

    @NotNull(message = "请选择医生")
    private Long doctorUserId;

    @NotNull(message = "请选择就诊日期")
    private LocalDate scheduleDate;

    @NotNull(message = "请选择就诊时段")
    @Min(value = 1, message = "就诊时段不正确")
    @Max(value = 2, message = "就诊时段不正确")
    private Integer period;

    /** 可选：指定就诊序号；不传自动分配首个空闲号源 */
    private Integer seqNo;

    @NotBlank(message = "患者姓名不能为空")
    private String patientName;

    @NotBlank(message = "患者手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "患者手机号格式不正确")
    private String patientPhone;

    @NotBlank(message = "患者身份证号不能为空")
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "患者身份证号格式不正确")
    private String patientIdCard;

    /** 收费金额 */
    @DecimalMin(value = "0", message = "收费金额不能为负数")
    private BigDecimal payAmount;
}
