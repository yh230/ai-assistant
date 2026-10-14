package com.hospital.mapper;

import com.hospital.entity.Hospital;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 医院 Mapper。
 */
@Mapper
public interface HospitalMapper extends BaseMapper<Hospital> {
}
