package com.api.dto;

import lombok.Data;

/**
 * 员工分页查询请求（hospital 员工管理页调用，过滤参数由调用方按数据权限传入）
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class UserPageRequest {

    /** 当前页码（从 1 开始） */
    private long pageNum = 1;

    /** 每页大小 */
    private long pageSize = 10;

    /** 搜索关键字（账号/姓名/手机号模糊匹配） */
    private String keyword;

    /** 医院 ID（可选过滤） */
    private Long hospitalId;

    /** 科室 ID（可选过滤） */
    private Long departmentId;

    /** 角色 ID（可选过滤） */
    private Long roleId;
}
