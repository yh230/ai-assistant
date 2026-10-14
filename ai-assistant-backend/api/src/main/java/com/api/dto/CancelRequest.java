package com.api.dto;

import lombok.Data;

/**
 * AI 取消预约入参
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
public class CancelRequest {

    /** 患者账号 ID */
    private Long patientId;

    /** 挂号记录 ID */
    private Long registrationId;

    /** 取消原因（可空） */
    private String reason;
}
