package com.aiassistant.registration.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 排班/号源配置视图。
 */
@Data
public class ScheduleVO {
    private Long id;
    private Long hospitalId;
    private String hospitalName;
    private Long departmentId;
    private String departmentName;
    private Long doctorUserId;
    private String doctorName;
    private LocalDate scheduleDate;
    /** 1=上午 2=下午 */
    private Integer period;
    private String periodText;
    /** 号源总数 */
    private Integer totalLimit;
    /** 已挂号数（冗余） */
    private Integer registeredCount;
    /** 余量 = totalLimit - registeredCount */
    private Integer remaining;
    /** 1=启用 0=停用 */
    private Integer status;
    private String statusText;
    private String remark;
    private LocalDateTime createTime;
}
