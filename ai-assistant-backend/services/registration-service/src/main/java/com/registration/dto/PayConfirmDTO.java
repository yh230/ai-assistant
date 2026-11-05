package com.registration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 患者确认支付（模拟支付成功）。手机号+身份证双因子校验预约归属。
 */
@Data
public class PayConfirmDTO {

    /** 患者手机号，用于校验预约归属 */
    @NotBlank(message = "患者手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "患者手机号格式不正确")
    private String patientPhone;

    /** 患者身份证号，用于校验预约归属 */
    @NotBlank(message = "患者身份证号不能为空")
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "患者身份证号格式不正确")
    private String patientIdCard;
}
