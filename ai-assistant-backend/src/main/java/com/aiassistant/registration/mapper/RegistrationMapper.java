package com.aiassistant.registration.mapper;

import com.aiassistant.registration.entity.Registration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 挂号记录数据访问层
 *
 * @author yh
 * @date 2026-10-10
 */
public interface RegistrationMapper extends BaseMapper<Registration> {

    /**
     * 按维度分组统计挂号量（维度与范围由后端白名单控制，防止 SQL 注入）
     *
     * @param groupColumn 分组列（doctor_id / department_id / status / schedule_date）
     * @param whereSql    数据权限范围条件（由 DataScopeResolver 推导）
     * @return 分组统计结果（dim/cnt）
     */
    @Select("SELECT ${groupColumn} AS dim, COUNT(*) AS cnt FROM registration WHERE ${whereSql} GROUP BY ${groupColumn} ORDER BY cnt DESC")
    List<Map<String, Object>> groupStats(@Param("groupColumn") String groupColumn,
                                          @Param("whereSql") String whereSql);
}
