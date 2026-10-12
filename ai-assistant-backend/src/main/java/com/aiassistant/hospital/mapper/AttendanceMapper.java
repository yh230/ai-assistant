package com.aiassistant.hospital.mapper;

import com.aiassistant.hospital.entity.Attendance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 出勤 Mapper。
 */
@Mapper
public interface AttendanceMapper extends BaseMapper<Attendance> {

    /**
     * 出勤统计：按维度（department/hospital/status）分组计数。
     * <p>
     * 维度由 SQL 片段动态拼接，调用方需自行保证 groupColumn 为白名单列（department_id / hospital_id / status），
     * 防止 SQL 注入。此处仅用于内部统计，groupColumn 由后端枚举控制。
     */
    @Select("SELECT ${groupColumn} AS dim, COUNT(*) AS cnt FROM attendance "
            + "WHERE ${whereSql} GROUP BY ${groupColumn} ORDER BY cnt DESC")
    List<Map<String, Object>> groupStats(@Param("groupColumn") String groupColumn,
                                         @Param("whereSql") String whereSql);
}
