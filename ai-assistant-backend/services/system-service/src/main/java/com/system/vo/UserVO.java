package com.system.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 员工视图对象（不含密码）。
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String name;

    private String phone;

    /** 邮箱（非必填） */
    private String email;

    /** 账号状态：1-启用，0-禁用 */
    private Integer status;

    /** 关联角色ID集合 */
    private List<Long> roleIds;

    /** 关联角色名称（逗号拼接，便于展示） */
    private String roleNames;

    private LocalDateTime createTime;
}
