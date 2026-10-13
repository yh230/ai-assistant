package com.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工用户表。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号 */
    private String username;

    /** BCrypt 加密密码 */
    private String password;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 邮箱（非必填） */
    private String email;

    /** 账号状态：1-启用，0-禁用 */
    private Integer status;

    /** 所属医院ID（医院模块：一人一医院，冗余便于统计） */
    private Long hospitalId;

    /** 所属科室ID（医院模块：一人一科室） */
    private Long departmentId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
