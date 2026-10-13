package com.registration.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ScheduleQueryDTO {
    private Long doctorUserId;
    private Long departmentId;
    private Long hospitalId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Integer period;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
