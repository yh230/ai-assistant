package com.hospital.dto;

import com.api.dto.UserCreateRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医院员工新增请求（账号/密码/姓名/电话/邮箱/状态/角色/医院/科室绑定字段均由父类提供）
 * <p>
 * 继承 {@link UserCreateRequest}，字段与原请求体一致，由员工服务经 Feign 转发用户中心创建。
 *
 * @author yh
 * @date 2026-10-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EmployeeSaveDTO extends UserCreateRequest {
}
