package com.aiassistant.common.dto;

import lombok.Data;

/**
 * 重置密码请求（newPassword 为空则重置为默认密码）
 * <p>
 * 变更点（DTO 合并与继承）：由 system/hospital 两份同名 DTO 合并而来，统一存放于 common/dto，
 * 供系统员工端与医院员工端共用
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
public class ResetPasswordDTO {

    /** 新密码，可空（空则重置为默认密码） */
    private String newPassword;
}
