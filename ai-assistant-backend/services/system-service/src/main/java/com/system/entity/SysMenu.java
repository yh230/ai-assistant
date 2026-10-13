package com.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜单权限表：区分菜单（路由）与按钮操作权限。
 */
@Data
@TableName("sys_menu")
public class SysMenu {

    /** 主键 */
    @TableId(type = IdType.AUTO)
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

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
