package com.registration.service;

import com.common.result.PageResult;
import com.registration.dto.ScheduleQueryDTO;
import com.registration.dto.ScheduleSaveDTO;
import com.registration.vo.ScheduleVO;

import java.util.List;

/**
 * 医生出诊排班 / 号源配置服务（门诊管理员维护，本院数据）。
 */
public interface DoctorScheduleService {

    PageResult<ScheduleVO> page(ScheduleQueryDTO q);

    ScheduleVO get(Long id);

    /** 新增/编辑排班；医院/科室由 doctorUserId 推导；保存后按排班生成号源 */
    ScheduleVO save(ScheduleSaveDTO dto);

    ScheduleVO update(Long id, ScheduleSaveDTO dto);

    /** 软删除：停用排班并释放空闲号源 */
    void remove(Long id);

    /** 手动按排班生成/重置号源 */
    void generateSources(Long scheduleId);

    /** 公开：返回有出诊排班的医生列表（患者预约选号用，只读启用中排班） */
    List<ScheduleVO> listPublic(java.time.LocalDate date, Long departmentId);
}
