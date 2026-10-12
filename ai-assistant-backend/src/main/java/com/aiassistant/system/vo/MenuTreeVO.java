package com.aiassistant.system.vo;

import lombok.Data;

import java.util.List;

/**
 * 菜单树节点。
 */
@Data
public class MenuTreeVO {

    private Long id;

    private Long parentId;

    private String menuName;

    private String path;

    private String component;

    private String perms;

    /** 类型：1-菜单，2-按钮 */
    private Integer type;

    private Integer sort;

    /** 子节点 */
    private List<MenuTreeVO> children;
}
