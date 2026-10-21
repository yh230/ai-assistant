package com.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对外排班查询结果
 *
 * @author yh
 * @date 2026-10-21
 */
@Data
public class ScheduleQueryResultDTO {

    /** 排班列表 */
    private List<DoctorScheduleInfoDTO> items = new ArrayList<>();

    /** 是否因扫描或返回上限被截断 */
    private boolean truncated;

    /**
     * 构造空结果
     *
     * @return 空结果
     */
    public static ScheduleQueryResultDTO empty() {
        return new ScheduleQueryResultDTO();
    }
}
