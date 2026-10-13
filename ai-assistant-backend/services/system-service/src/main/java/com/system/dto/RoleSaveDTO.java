package com.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 角色新增/编辑请求。
 */
@Data
public class RoleSaveDTO {

    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    private String roleDesc;
}
