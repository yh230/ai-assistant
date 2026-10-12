package com.aiassistant.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 编辑员工请求。
 */
@Data
public class UserUpdateDTO {

    @NotBlank(message = "姓名不能为空")
    private String name;

    private String phone;

    /** 邮箱（非必填） */
    private String email;

    @NotNull(message = "状态不能为空")
    private Integer status;

    /** 关联角色ID集合 */
    private List<Long> roleIds;
}
