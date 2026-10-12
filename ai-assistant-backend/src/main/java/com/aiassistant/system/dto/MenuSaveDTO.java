package com.aiassistant.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 菜单新增/编辑请求。
 */
@Data
public class MenuSaveDTO {

    /** 主键（编辑时必填，新增时不传） */
    private Long id;

    /** 父菜单ID，顶级菜单为 0 */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** 路由 path */
    private String path;

    /** 组件路径 */
    private String component;

    /** 权限标识，如 system:user:add（按钮级） */
    private String perms;

    /** 类型：1-菜单，2-按钮 */
    private Integer type;

    /** 排序 */
    private Integer sort;
}
