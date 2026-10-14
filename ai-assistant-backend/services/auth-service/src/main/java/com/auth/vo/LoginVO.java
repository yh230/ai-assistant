package com.auth.vo;

import lombok.Data;

/**
 * 登录返回：JWT 令牌
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class LoginVO {

    /** 令牌（jjwt 签发的 JWT） */
    private String token;

    /** 令牌类型，固定 Bearer */
    private String tokenType = "Bearer";

    /** 刷新令牌（随机串，用于静默续期） */
    private String refreshToken;
}
