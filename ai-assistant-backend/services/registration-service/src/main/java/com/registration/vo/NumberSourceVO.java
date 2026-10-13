package com.registration.vo;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 号源池视图（含余量）。
 */
@Data
public class NumberSourceVO {
    private Long scheduleId;
    private Long doctorUserId;
    private String doctorName;
    private LocalDate scheduleDate;
    private Integer period;
    private String periodText;
    private Integer totalLimit;
    /** 已占用数 */
    private Integer usedCount;
    /** 余量（空闲数） */
    private Integer remainingCount;
    /** 号位明细 */
    private List<SourceItem> items;

    @Data
    public static class SourceItem {
        private Long id;
        private Integer seqNo;
        /** 1=空闲 2=已占用 */
        private Integer status;
        private String statusText;
        private Long registrationId;
    }
}
