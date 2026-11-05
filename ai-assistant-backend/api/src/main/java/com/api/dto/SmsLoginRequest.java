package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
    @Pattern(regexp = "^(staff|patient)$", message = "身份类型不正确")
    private String identity;

    /** 手机号 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    /** 短信验证码 */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{4,8}$", message = "验证码格式不正确")
    private String code;
}
