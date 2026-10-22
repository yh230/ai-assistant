package com.registration.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;

/**
 * 患者线上预约（匿名公开接口）。seqNo 不传则自动选首个空闲号源。
 */
@Data
public class AppointmentCreateDTO {

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

    /** 患者账号 ID（AI 挂号传入；匿名预约为空） */
    private Long patientId;
}
