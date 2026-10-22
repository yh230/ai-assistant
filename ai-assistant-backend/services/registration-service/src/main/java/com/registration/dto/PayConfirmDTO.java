package com.registration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 患者确认支付（模拟支付成功）。校验身份证确保是本人操作。
 */
@Data
public class PayConfirmDTO {

    /** 患者身份证号，用于校验预约归属 */
    @NotBlank(message = "患者身份证号不能为空")
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "患者身份证号格式不正确")
    private String patientIdCard;
}
