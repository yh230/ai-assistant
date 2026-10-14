package com.aiassistant.repository;

import com.aiassistant.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 角色数据访问层。
 *
 * @author AI Assistant
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, String> {

    /**
     * 查询全部已启用角色，按排序号、ID 升序。
     *
     * @return 已启用角色列表
     */
    List<Role> findByEnabledTrueOrderBySortOrderAscIdAsc();

    /**
     * 查询全部角色（含禁用），按排序号、ID 升序。
     *
     * @return 全量角色列表
     */
    List<Role> findAllByOrderBySortOrderAscIdAsc();
}
