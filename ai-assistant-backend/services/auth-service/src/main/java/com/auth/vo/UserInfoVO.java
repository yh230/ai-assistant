package com.auth.vo;

import com.api.dto.MenuNodeDTO;
import lombok.Data;

import java.util.List;

/**
 * 当前登录用户信息：用户信息 + 角色集合 + 按钮权限标识集合 + 菜单树。
 */
@Data
public class UserInfoVO {

    /** 用户ID */
    private Long userId;

    /** 账号 */
    private String username;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 邮箱（非必填） */
    private String email;

    /** 账号状态：1-启用，0-禁用 */
    private Integer status;

    /** 所属医院ID（未绑定为 null） */
    private Long hospitalId;

    /** 角色编码集合（如 ["admin"]） */
    private List<String> roles;

    /** 按钮权限标识集合（如 ["system:user:add"]） */
    private List<String> permissions;

    /** 可访问菜单树（仅菜单类型节点） */
    private List<MenuNodeDTO> menus;
}
