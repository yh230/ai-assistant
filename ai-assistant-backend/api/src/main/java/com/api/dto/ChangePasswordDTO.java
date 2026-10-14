package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改密码：需校验原密码，避免越权改密
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class ChangePasswordDTO {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
