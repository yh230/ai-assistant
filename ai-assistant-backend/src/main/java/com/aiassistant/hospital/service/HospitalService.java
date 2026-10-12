package com.aiassistant.hospital.service;

import com.aiassistant.common.result.PageResult;
import com.aiassistant.hospital.dto.HospitalSaveDTO;
import com.aiassistant.hospital.vo.HospitalVO;

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
}
