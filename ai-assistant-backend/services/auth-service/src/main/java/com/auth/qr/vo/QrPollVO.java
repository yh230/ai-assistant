package com.auth.qr.vo;

import lombok.Data;

/**
 * 扫码轮询结果（CONFIRMED 时携带令牌对）
 *
 * @author yh
 * @date 2026-10-16
 */
@Data
public class QrPollVO {

    /** WAITING / CONFIRMED / EXPIRED */
    private String status;

    /** 访问令牌（仅 CONFIRMED） */
    private String token;

    /** 刷新令牌（仅 CONFIRMED） */
    private String refreshToken;

    /** 令牌类型（仅 CONFIRMED） */
    private String tokenType;
}
