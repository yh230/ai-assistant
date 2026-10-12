package com.aiassistant.hospital.dto;

import com.aiassistant.system.dto.UserSaveDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医院员工新增请求（底层仍写入 sys_user，并绑定医院/科室/岗位角色）
 * <p>
 * 变更点（DTO 合并与继承）：改为继承 {@link UserSaveDTO}，账号/密码/姓名/电话/邮箱/状态/角色字段
 * 统一由父类提供，本类仅保留医院扩展字段
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EmployeeSaveDTO extends UserSaveDTO {

    /** 所属医院 ID（管理端可传；科室主任场景由后端强制本科室，前端传参无效） */
    private Long hospitalId;

    /** 所属科室 ID */
    private Long departmentId;
}
