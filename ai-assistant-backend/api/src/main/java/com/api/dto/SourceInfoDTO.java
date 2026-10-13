package com.api.dto;

import lombok.Data;

/**
 * 号源池简要信息（供 AI 工具与前端确认摘要）
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
public class SourceInfoDTO {

    /** 排班 ID */
    private Long scheduleId;

    /** 医生用户 ID */
    private Long doctorUserId;

    /** 医生姓名 */
    private String doctorName;

    /** 出诊日期（yyyy-MM-dd） */
    private String scheduleDate;

    /** 时段：1-上午，2-下午 */
    private Integer period;

    /** 时段名称 */
    private String periodText;

    /** 号源总数 */
    private Integer totalLimit;

    /** 已占用数 */
    private Integer usedCount;

    /** 余量（空闲数） */
    private Integer remainingCount;
}
