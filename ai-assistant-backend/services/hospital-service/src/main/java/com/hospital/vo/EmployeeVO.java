package com.hospital.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 医院员工视图对象（含医院/科室名称、岗位角色）。
 * <p>
 * 独立类（不继承系统用户视图）：字段为原用户视图字段与员工扩展字段的并集，
 * 保证前端 JSON 契约不变。
 */
@Data
public class EmployeeVO {

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

    /** 所属医院 ID */
    private Long hospitalId;

    /** 所属医院名称 */
    private String hospitalName;

    /** 所属科室 ID */
    private Long departmentId;

    /** 所属科室名称 */
    private String departmentName;
}
