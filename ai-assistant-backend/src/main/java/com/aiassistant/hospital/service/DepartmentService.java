package com.aiassistant.hospital.service;

import com.aiassistant.common.result.PageResult;
import com.aiassistant.hospital.dto.DepartmentSaveDTO;
import com.aiassistant.hospital.vo.DepartmentVO;

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
}
