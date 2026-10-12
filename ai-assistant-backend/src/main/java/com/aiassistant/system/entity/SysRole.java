package com.aiassistant.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色表。
 */
@Data
@TableName("sys_role")
public class SysRole {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码（唯一），如 admin */
    private String roleCode;

    /** 角色名称 */
    private String roleName;

    /** 角色描述 */
    private String roleDesc;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
