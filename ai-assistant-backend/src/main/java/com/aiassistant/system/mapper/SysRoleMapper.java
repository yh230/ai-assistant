package com.aiassistant.system.mapper;

import com.aiassistant.system.entity.SysRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色 Mapper。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT r.* FROM sys_role r "
            + "LEFT JOIN sys_user_role ur ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<SysRole> selectByUserId(@Param("userId") Long userId);

    @Select("SELECT r.role_code FROM sys_role r "
            + "LEFT JOIN sys_user_role ur ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
