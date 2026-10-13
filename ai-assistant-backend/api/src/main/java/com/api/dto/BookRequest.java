package com.api.dto;

import lombok.Data;

/**
 * AI 预约入参
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
public class BookRequest {

    /** 患者账号 ID */
    private Long patientId;

    /** 患者姓名 */
    private String patientName;

    /** 患者手机号 */
    private String patientPhone;

    /** 医生用户 ID */
    private Long doctorUserId;

    /** 就诊日期（yyyy-MM-dd） */
    private String scheduleDate;

    /** 时段：1-上午，2-下午 */
    private Integer period;

    /** 就诊序号（可空，空则自动分配） */
    private Integer seqNo;
}
