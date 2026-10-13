package com.registration.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 患者线上预约（匿名公开接口）。seqNo 不传则自动选首个空闲号源。
 */
@Data
public class AppointmentCreateDTO {
    private Long doctorUserId;
    private LocalDate scheduleDate;
    private Integer period;
    /** 可选：指定就诊序号；不传自动分配首个空闲号源 */
    private Integer seqNo;
    private String patientName;
    private String patientPhone;
    private String patientIdCard;
}
