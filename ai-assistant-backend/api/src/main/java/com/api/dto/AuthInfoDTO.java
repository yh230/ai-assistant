package com.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 登录认证信息：用户简要信息 + 角色/权限 + 菜单树 + 数据权限范围
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class AuthInfoDTO {

    /** 用户简要信息 */
    private UserBriefDTO user;

    /** 角色编码列表 */
    private List<String> roles;

    /** 权限标识列表 */
    private List<String> perms;

    /** 菜单树 */
    private List<MenuNodeDTO> menus;

    /** 所属医院 ID（数据权限范围） */
    private Long hospitalId;

    /** 所属科室 ID（数据权限范围） */
    private Long departmentId;
}
