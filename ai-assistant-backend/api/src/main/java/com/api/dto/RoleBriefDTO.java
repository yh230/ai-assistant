package com.api.dto;

import lombok.Data;

/**
 * 角色简要信息（hospital 员工表单角色下拉）
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class RoleBriefDTO {

    /** 角色 ID */
    private Long id;

    /** 角色编码 */
    private String roleCode;

    /** 角色名称 */
    private String roleName;
}
