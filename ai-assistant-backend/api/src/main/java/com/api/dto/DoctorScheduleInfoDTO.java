package com.api.dto;

import lombok.Data;

/**
 * 对外排班查询的单条结果
 *
 * @author yh
 * @date 2026-10-21
 */
@Data
public class DoctorScheduleInfoDTO {

    /** 医院 ID */
    private Long hospitalId;

    /** 医院名称 */
    private String hospitalName;

    /** 科室 ID */
    private Long departmentId;

    /** 科室名称 */
    private String departmentName;

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

    /** 余量（空闲号源数） */
    private Integer remainingCount;
}
