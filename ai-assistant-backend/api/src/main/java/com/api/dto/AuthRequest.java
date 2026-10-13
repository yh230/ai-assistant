package com.api.dto;

import lombok.Data;

/**
 * 登录请求体
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class AuthRequest {

    /** 登录账号 */
    private String username;

    /** 登录密码 */
    private String password;
}
