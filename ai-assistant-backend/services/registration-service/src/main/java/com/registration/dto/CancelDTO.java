package com.registration.dto;

import lombok.Data;

/**
 * 患者取消本人预约（公开接口，需身份证校验）。
 */
@Data
public class CancelDTO {
    /** 患者身份证号，用于校验预约归属 */
    private String patientIdCard;
    /** 取消原因（可选） */
    private String reason;
}
