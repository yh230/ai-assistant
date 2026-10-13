package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 编辑员工请求
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class UserUpdateRequest {

    @NotBlank(message = "姓名不能为空")
    private String name;

    private String phone;

    /** 邮箱（非必填） */
    private String email;

    @NotNull(message = "状态不能为空")
    private Integer status;

    /** 关联角色ID集合 */
    private List<Long> roleIds;

    /** 所属医院 ID（医院模块绑定，可为空） */
    private Long hospitalId;

    /** 所属科室 ID（医院模块绑定，可为空） */
    private Long departmentId;
}
