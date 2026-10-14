package com.auth.dto;

import lombok.Data;

/**
 * 刷新令牌请求体
 *
 * @author yh
 * @date 2026-10-14
 */
@Data
public class RefreshTokenRequest {

    /** 刷新令牌串 */
    private String refreshToken;
}
