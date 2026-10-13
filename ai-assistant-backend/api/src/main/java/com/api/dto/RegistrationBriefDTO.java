package com.api.dto;

import lombok.Data;

/**
 * 预约记录简要信息
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
public class RegistrationBriefDTO {

    /** 挂号记录 ID */
    private Long id;

    /** 挂号凭证号 */
    private String registrationNo;

    /** 科室 ID */
    private Long departmentId;

    /** 科室名称 */
    private String departmentName;

    /** 医生姓名 */
    private String doctorName;

    /** 就诊日期（yyyy-MM-dd） */
    private String scheduleDate;

    /** 时段：1-上午，2-下午 */
    private Integer period;

    /** 时段名称 */
    private String periodText;

    /** 就诊序号 */
    private Integer seqNo;

    /** 状态：1-已预约，2-已取号，3-已就诊，4-已取消，5-已退号，6-过期失效 */
    private Integer status;

    /** 状态名称 */
    private String statusText;

    /** 预约锁定过期时间（字符串，可为空） */
    private String expireTime;
}
