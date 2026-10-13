package com.hospital.service;

import com.api.dto.OrgBriefDTO;
import com.common.result.PageResult;
import com.hospital.dto.HospitalSaveDTO;
import com.hospital.vo.HospitalVO;

/**
 * 医院管理服务（组织基础数据，仅院长/管理员可维护，无科室级数据隔离）。
 */
public interface HospitalService {

    PageResult<HospitalVO> pageHospitals(String keyword, long pageNum, long pageSize);

    java.util.List<HospitalVO> listAll();

    HospitalVO get(Long id);

    void create(HospitalSaveDTO dto);

    void update(Long id, HospitalSaveDTO dto);

    void delete(Long id);

    void changeStatus(Long id, Integer status);

    /** 按 ID 批量查询医院简要信息（内部接口，供服务间 Feign 名称回填） */
    java.util.List<OrgBriefDTO> batchBrief(java.util.List<Long> ids);
}
