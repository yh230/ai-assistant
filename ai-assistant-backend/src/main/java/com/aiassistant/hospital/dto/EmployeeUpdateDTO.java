package com.aiassistant.hospital.dto;

import com.aiassistant.system.dto.UserUpdateDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医院员工编辑请求（不含密码，改密走独立接口）
 * <p>
 * 变更点（DTO 合并与继承）：改为继承 {@link UserUpdateDTO}，姓名/电话/邮箱/状态/角色字段
 * 统一由父类提供，本类仅保留医院扩展字段
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EmployeeUpdateDTO extends UserUpdateDTO {

    /** 所属医院 ID */
    private Long hospitalId;

    /** 所属科室 ID */
    private Long departmentId;
}
