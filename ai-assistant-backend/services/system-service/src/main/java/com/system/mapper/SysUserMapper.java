package com.system.mapper;

import com.system.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 员工用户 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 查询指定角色码且启用的用户（按医院/科室可选过滤，供排班选择医生）
     */
    @Select("<script>"
            + "SELECT DISTINCT u.* FROM sys_user u "
            + "JOIN sys_user_role ur ON u.id = ur.user_id "
            + "JOIN sys_role r ON ur.role_id = r.id "
            + "WHERE r.role_code = #{roleCode} AND u.status = 1 "
            + "<if test='hospitalId != null'> AND u.hospital_id = #{hospitalId}</if> "
            + "<if test='departmentId != null'> AND u.department_id = #{departmentId}</if> "
            + "ORDER BY u.id ASC"
            + "</script>")
    List<SysUser> selectDoctors(@Param("roleCode") String roleCode,
                                @Param("hospitalId") Long hospitalId,
                                @Param("departmentId") Long departmentId);
}
