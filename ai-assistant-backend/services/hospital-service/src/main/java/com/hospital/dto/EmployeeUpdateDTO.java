package com.hospital.dto;

import com.api.dto.UserUpdateRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医院员工编辑请求（不含密码，改密走独立接口）
 * <p>
 * 继承 {@link UserUpdateRequest}，姓名/电话/邮箱/状态/角色/医院/科室绑定字段均由父类提供，
 * 由员工服务经 Feign 转发用户中心更新。
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EmployeeUpdateDTO extends UserUpdateRequest {
}
