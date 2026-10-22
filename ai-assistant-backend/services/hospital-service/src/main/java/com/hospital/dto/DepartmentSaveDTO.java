package com.hospital.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 科室新增/编辑请求。
 */
@Data
public class DepartmentSaveDTO {

    @NotNull(message = "所属医院不能为空")
    private Long hospitalId;

    @NotBlank(message = "科室名称不能为空")
    private String name;

    /** 科室主任用户ID（sys_user.id），可空 */
    private Long directorUserId;

    /** 状态：1-启用，0-禁用，默认启用 */
    @Min(value = 0, message = "状态值不正确")
    @Max(value = 1, message = "状态值不正确")
    private Integer status = 1;
}
