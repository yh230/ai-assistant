package com.aiassistant.hospital.service;

import com.aiassistant.common.result.PageResult;
import com.aiassistant.hospital.dto.AttendanceQueryDTO;
import com.aiassistant.hospital.dto.AttendanceSaveDTO;
import com.aiassistant.hospital.vo.AttendanceVO;

import java.util.Map;

/**
 * 出勤服务（核心数据权限模块）。
 * <p>
 * 所有读/写均按当前登录人 {@code 院长=全部 / 科室主任=本科室 / 普通员工=本人} 自动过滤与校验。
 */
public interface AttendanceService {

    PageResult<AttendanceVO> pageAttendance(AttendanceQueryDTO query);

    AttendanceVO get(Long id);

    void create(AttendanceSaveDTO dto);

    void update(Long id, AttendanceSaveDTO dto);

    void delete(Long id);

    /**
     * 出勤统计。
     * @param dimension department(按科室) / hospital(按医院) / status(按状态)
     * @return { dimension, rows:[ {dimId, dimName, cnt} ] }
     */
    Map<String, Object> stats(String dimension);
}
