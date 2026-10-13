package com.system.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 患者注册请求
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
public class PatientRegisterDTO {

    /** 手机号（登录名） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    /** 密码（明文提交，入库前 BCrypt 加密） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String password;

    /** 患者姓名 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名长度不能超过 50 个字符")
    private String name;

    /** 绑定医院 ID */
    @NotNull(message = "医院不能为空")
    private Long hospitalId;
}
