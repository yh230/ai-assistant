package com.api.dto;

import lombok.Data;

/**
 * 用户简要信息（跨服务返回）
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class UserBriefDTO {

    /** 用户 ID */
    private Long id;

    /** 登录账号 */
    private String username;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 账号状态：1-启用，0-禁用 */
    private Integer status;
}
