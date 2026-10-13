package com.common.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录用户权限快照（Redis 缓存于 auth:user:{userId}，权限/绑定变更时失效重建）
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSnapshot {

    /** 用户 ID */
    private Long userId;

    /** 登录账号 */
    private String username;

    /** 姓名 */
    private String name;

    /** 角色编码列表 */
    private List<String> roles;

    /** 权限标识列表 */
    private List<String> perms;

    /** 所属医院 ID（数据权限范围） */
    private Long hospitalId;

    /** 所属科室 ID（数据权限范围） */
    private Long departmentId;
}
