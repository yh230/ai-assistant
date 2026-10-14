package com.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 菜单树节点
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class MenuNodeDTO {

    /** 菜单 ID */
    private Long id;

    /** 父菜单 ID */
    private Long parentId;

    /** 菜单名称 */
    private String menuName;

    /** 路由地址 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 权限标识 */
    private String perms;

    /** 类型：1-菜单，2-按钮 */
    private Integer type;

    /** 显示顺序 */
    private Integer sort;

    /** 子节点 */
    private List<MenuNodeDTO> children;
}
