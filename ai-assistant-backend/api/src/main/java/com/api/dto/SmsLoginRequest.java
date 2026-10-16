package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 手机号验证码登录入参
 *
 * @author yh
 * @date 2026-10-16
 */
@Data
public class SmsLoginRequest {

    /** 身份：staff / patient */
    @NotBlank(message = "身份不能为空")
    private String identity;

    /** 手机号 */
    @NotBlank(message = "手机号不能为空")
    private String phone;

    /** 短信验证码 */
    @NotBlank(message = "验证码不能为空")
    private String code;
}
