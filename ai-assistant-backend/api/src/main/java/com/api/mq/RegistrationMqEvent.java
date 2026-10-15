package com.api.mq;

import lombok.Data;

/**
 * 预约域 Kafka 事件负载（站内信与管理端过滤使用）
 *
 * @author yh
 * @date 2026-10-14
 */
@Data
public class RegistrationMqEvent {

    /** 预约创建 */
    public static final String APPOINTMENT_CREATED = "APPOINTMENT_CREATED";

    /** 预约取消 */
    public static final String APPOINTMENT_CANCELLED = "APPOINTMENT_CANCELLED";

    /** 挂号退费 */
    public static final String REGISTRATION_REFUNDED = "REGISTRATION_REFUNDED";

    /** 排班取消 */
    public static final String SCHEDULE_CANCELLED = "SCHEDULE_CANCELLED";

    /** 预约过期 */
    public static final String APPOINTMENT_EXPIRED = "APPOINTMENT_EXPIRED";

    /** 预约单号 */
    private String registrationNo;

    /** 患者ID */
    private Long patientId;

    /** 医院ID（站内信按医院隔离与管理端过滤） */
    private Long hospitalId;

    /** 医生姓名 */
    private String doctorName;

    /** 科室名称 */
    private String departmentName;

    /** 就诊日期 */
    private String visitDate;

    /** 时段（1=上午，2=下午） */
    private Integer period;

    /** 预约事由 */
    private String reason;
}
