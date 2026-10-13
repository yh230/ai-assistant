package com.api.dto;

import lombok.Data;

/**
 * 个人信息修改（手机号 / 邮箱），均可选填、非必填
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class ProfileUpdateDTO {

    /** 手机号（可选） */
    private String phone;

    /** 邮箱（可选，非必填） */
    private String email;
}
