package com.system.service;

import com.api.dto.RoleBriefDTO;
import com.common.result.PageResult;
import com.system.dto.AssignMenuDTO;
import com.system.dto.RoleSaveDTO;
import com.system.vo.RoleVO;

import java.util.List;

/**
 * 角色服务。
 */
public interface RoleService {

    /** 分页查询角色 */
    PageResult<RoleVO> pageRoles(String keyword, long pageNum, long pageSize);

    /** 新增角色 */
    void createRole(RoleSaveDTO dto);

    /** 编辑角色 */
    void updateRole(Long id, RoleSaveDTO dto);

    /** 删除角色 */
    void deleteRole(Long id);

    /** 为角色分配菜单/按钮权限 */
    void assignMenus(Long roleId, AssignMenuDTO dto);

    /** 查询角色已分配的菜单ID集合（前端回显勾选用） */
    List<Long> getRoleMenuIds(Long roleId);

    /** 角色简要列表（hospital 员工表单角色下拉经 Feign 调用） */
    List<RoleBriefDTO> listBriefRoles();
}
