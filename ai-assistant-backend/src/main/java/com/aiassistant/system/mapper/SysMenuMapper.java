package com.aiassistant.system.mapper;

import com.aiassistant.system.entity.SysMenu;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 菜单权限 Mapper。
 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {

    /**
     * 查询用户可访问的菜单（含按钮），按 sort 排序。
     */
    @Select("SELECT DISTINCT m.* FROM sys_menu m "
            + "LEFT JOIN sys_role_menu rm ON m.id = rm.menu_id "
            + "LEFT JOIN sys_user_role ur ON rm.role_id = ur.role_id "
            + "WHERE ur.user_id = #{userId} ORDER BY m.sort ASC")
    List<SysMenu> selectByUserId(@Param("userId") Long userId);

    /**
     * 查询用户拥有的权限标识集合（多角色取并集，菜单节点 type=1 与按钮 type=2 均参与）。
     * <p>
     * 不可加 {@code m.type = 2} 限制：{@code :list} 权限（如 system:role:list）
     * 挂在 type=1 菜单节点上，只取按钮会导致这些权限缺失，使分页接口 hasAuthority 失败。
     */
    @Select("SELECT DISTINCT m.perms FROM sys_menu m "
            + "LEFT JOIN sys_role_menu rm ON m.id = rm.menu_id "
            + "LEFT JOIN sys_user_role ur ON rm.role_id = ur.role_id "
            + "WHERE ur.user_id = #{userId} "
            + "AND m.perms IS NOT NULL AND m.perms <> ''")
    List<String> selectPermsByUserId(@Param("userId") Long userId);
}
