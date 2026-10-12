package com.aiassistant.common.dto;

import lombok.Data;

/**
 * 重置密码请求（newPassword 为空则重置为默认密码）
 * <p>
 * system 与 hospital 共用的重置密码请求体
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
public class ResetPasswordDTO {

    /** 新密码，可空（空则重置为默认密码） */
    private String newPassword;
}
