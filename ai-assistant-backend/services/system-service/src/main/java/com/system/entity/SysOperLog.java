package com.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志表：各服务经内部接口上报的操作审计记录
 *
 * @author yh
 * @date 2026-10-14
 */
@Data
@TableName("sys_oper_log")
public class SysOperLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

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

    /** 操作时间 */
    private LocalDateTime operTime;
}
