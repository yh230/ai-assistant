package com.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色视图对象。
 */
@Data
public class RoleVO {

    private Long id;

    private String roleCode;

    private String roleName;

    private String roleDesc;

    private LocalDateTime createTime;
}
