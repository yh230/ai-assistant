package com.aiassistant.repository;

import com.aiassistant.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    /**
     * 按角色域查询已启用角色，按排序号、ID 升序。
     *
     * @param scope 角色域（STAFF/PATIENT）
     * @return 已启用角色列表
     */
    List<Role> findByScopeAndEnabledTrueOrderBySortOrderAscIdAsc(String scope);

    /**
     * 按角色域查询全部角色（含禁用），按排序号、ID 升序。
     *
     * @param scope 角色域（STAFF/PATIENT）
     * @return 角色列表
     */
    List<Role> findByScopeOrderBySortOrderAscIdAsc(String scope);

    /**
     * 按患者角色唯一键查询（域 + 医院 + 会话类型）。
     *
     * @param scope       角色域
     * @param hospitalId  医院ID
     * @param sessionType 会话类型
     * @return 匹配的角色
     */
    Optional<Role> findByScopeAndHospitalIdAndSessionType(String scope, Long hospitalId, String sessionType);

    /**
     * 查询某医院全部患者角色（含禁用），按排序号、ID 升序。
     *
     * @param scope      角色域
     * @param hospitalId 医院ID
     * @return 角色列表
     */
    List<Role> findByScopeAndHospitalIdOrderBySortOrderAscIdAsc(String scope, Long hospitalId);
}
