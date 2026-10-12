package com.aiassistant.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 新增员工请求。
 */
@Data
public class UserSaveDTO {

    @NotBlank(message = "账号不能为空")
    private String username;

    /** 密码可空，为空时使用默认密码 */
    private String password;

    @NotBlank(message = "姓名不能为空")
    private String name;

    private String phone;

    /** 邮箱（非必填） */
    private String email;

    /** 账号状态：1-启用，0-禁用，默认启用 */
    private Integer status = 1;

    /** 关联角色ID集合 */
    private List<Long> roleIds;
}
