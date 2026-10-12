package com.aiassistant.hospital.vo;

import com.aiassistant.system.vo.UserVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医院员工视图对象（基于 sys_user，含医院/科室名称、岗位角色）
 * <p>
 * 变更点（DTO 合并与继承）：改为继承 {@link UserVO}，员工基础字段统一由父类提供，
 * 本类仅保留医院/科室扩展字段
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EmployeeVO extends UserVO {

    /** 所属医院 ID */
    private Long hospitalId;

    /** 所属医院名称 */
    private String hospitalName;

    /** 所属科室 ID */
    private Long departmentId;

    /** 所属科室名称 */
    private String departmentName;
}
