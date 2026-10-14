package com.registration.dto;

import lombok.Data;

/**
 * 患者确认支付（模拟支付成功）。校验身份证确保是本人操作。
 */
@Data
public class PayConfirmDTO {
    /** 患者身份证号，用于校验预约归属 */
    private String patientIdCard;
}
