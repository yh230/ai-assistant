package com.aiassistant.hospital.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 科室新增/编辑请求。
 */
@Data
public class DepartmentSaveDTO {

    @NotNull(message = "所属医院不能为空")
    private Long hospitalId;

    private String name;

    /** 科室主任用户ID（sys_user.id），可空 */
    private Long directorUserId;

    /** 状态：1-启用，0-禁用，默认启用 */
    private Integer status = 1;
}
