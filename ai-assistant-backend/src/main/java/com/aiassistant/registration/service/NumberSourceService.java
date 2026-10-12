package com.aiassistant.registration.service;

import com.aiassistant.registration.vo.NumberSourceVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 号源池服务（只读人员系统排班，生成号源，提供余量查询）。
 */
public interface NumberSourceService {

    /** 按医生/日期/时段查询号源池列表（含余量） */
    List<NumberSourceVO> listPools(Long doctorUserId, Long departmentId, LocalDate date, Integer period);

    /** 单个排班的号源池明细 */
    NumberSourceVO detail(Long scheduleId);

    /** 内部：按排班生成/重置号源（删除空闲号源后重生成） */
    void generateForSchedule(com.aiassistant.registration.entity.DoctorSchedule schedule);
}
