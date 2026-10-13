package com.hospital.service;

import com.api.dto.OrgBriefDTO;
import com.common.result.PageResult;
import com.hospital.dto.DepartmentSaveDTO;
import com.hospital.vo.DepartmentVO;

import java.util.List;

/**
 * 科室管理服务（组织基础数据，仅院长/管理员可维护；列表按数据权限过滤）。
 */
public interface DepartmentService {

    PageResult<DepartmentVO> pageDepartments(String keyword, Long hospitalId, long pageNum, long pageSize);

    /** 下拉用：返回当前登录人可见的科室（院长/管理员全部，科室主任仅本科室） */
    List<DepartmentVO> listByHospital(Long hospitalId);

    DepartmentVO get(Long id);

    void create(DepartmentSaveDTO dto);

    void update(Long id, DepartmentSaveDTO dto);

    void delete(Long id);

    void changeStatus(Long id, Integer status);

    /** 按 ID 批量查询科室简要信息（内部接口，供服务间 Feign 名称回填） */
    List<OrgBriefDTO> batchBrief(List<Long> ids);

    /** 科室简要列表（内部接口，可按医院过滤） */
    List<OrgBriefDTO> listBrief(Long hospitalId);
}
