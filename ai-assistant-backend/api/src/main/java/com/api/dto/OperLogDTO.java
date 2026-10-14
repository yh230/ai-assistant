package com.api.dto;

import lombok.Data;

/**
 * 操作日志上报数据
 *
 * @author yh
 * @date 2026-10-14
 */
@Data
public class OperLogDTO {

    /** 业务模块 */
    private String module;

    /** 操作动作 */
    private String action;

    /** 操作人ID（匿名为空） */
    private Long userId;

    /** 操作人账号（匿名为空） */
    private String username;

    /** 身份：staff-员工，patient-患者（匿名为空） */
    private String identity;

    /** HTTP方法 */
    private String method;

    /** 请求路径 */
    private String requestUri;

    /** 请求参数（敏感字段脱敏、超长截断） */
    private String params;

    /** 操作IP */
    private String ip;

    /** 结果：0-成功，1-失败 */
    private Integer status;

    /** 错误摘要（失败时） */
    private String errorMsg;

    /** 耗时毫秒 */
    private Long costMs;
}
