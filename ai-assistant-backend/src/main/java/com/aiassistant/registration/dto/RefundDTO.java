package com.aiassistant.registration.dto;

import lombok.Data;

/**
 * 窗口退号（收费员，已登录鉴权）。仅对已取号且未就诊记录退号并退款。
 */
@Data
public class RefundDTO {
    /** 退号原因（可选） */
    private String reason;
}
