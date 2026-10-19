package com.hospital.service.impl;

import com.api.client.UserClient;
import com.api.dto.RoleBriefDTO;
import com.api.dto.UserBriefDTO;
import com.api.dto.UserPageRequest;
import com.common.auth.UserSnapshot;
import com.common.constant.RoleConstants;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.DataScope;
import com.common.security.DataScopeResolver;
import com.common.security.RoleGrantPolicy;
import com.common.security.SecurityUtils;
import com.common.support.StatusValidator;
import com.hospital.dto.EmployeeQueryDTO;
import com.hospital.dto.EmployeeSaveDTO;
import com.hospital.dto.EmployeeUpdateDTO;
import com.hospital.entity.Department;
import com.hospital.entity.Hospital;
import com.hospital.mapper.DepartmentMapper;
import com.hospital.mapper.HospitalMapper;
import com.hospital.service.EmployeeService;
import com.hospital.vo.EmployeeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 医院员工服务实现（账号读写经用户中心 Feign，医院/科室名称由本库回填）
 * <p>
 * 数据权限：
 * <ul>
 *     <li>分页过滤参数由 {@link DataScopeResolver} 解析：院长→本院、科室主任→本科室、管理员→按前端条件、本人→仅本人</li>
 *     <li>新增/编辑：院长强制本院、科室主任强制本科室（前端越权传参无效）</li>
 *     <li>删除/改状态/重置密码 等操作先按员工归属做范围校验</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final UserClient userClient;
    private final HospitalMapper hospitalMapper;
    private final DepartmentMapper departmentMapper;
    private final DataScopeResolver dataScopeResolver;

    /**
     * 分页查询员工列表（过滤参数按当前登录人数据范围收敛后经用户中心分页）
     *
     * @param query 分页与筛选条件
     * @return 员工分页结果
     */
    @Override
    public PageResult<EmployeeVO> pageEmployees(EmployeeQueryDTO query) {
        DataScope scope = dataScopeResolver.resolve();
        if (scope.isSelf()) {
            return pageSelf(query, scope);
        }
        UserPageRequest req = new UserPageRequest();
        req.setPageNum(query.getPageNum());
        req.setPageSize(query.getPageSize());
        req.setKeyword(query.getKeyword());
        if (scope.isHospital()) {
            // 院长：锁定本院，仅允许在本院内按科室二次筛选（前端传别的医院无效）
            req.setHospitalId(scope.getHospitalId());
            if (query.getDepartmentId() != null) {
                req.setDepartmentId(query.getDepartmentId());
            }
        } else if (scope.isDepartment()) {
            // 科室主任：仅本科室
            req.setHospitalId(scope.getHospitalId());
            req.setDepartmentId(scope.getDepartmentId());
        } else {
            req.setHospitalId(query.getHospitalId());
            req.setDepartmentId(query.getDepartmentId());
        }
        Result<PageResult<UserBriefDTO>> result = userClient.page(req);
        PageResult<UserBriefDTO> page = unwrap(result, "查询员工列表失败");
        return PageResult.of(page.getTotal(), page.getPageNum(), page.getPageSize(), enrich(page.getList()));
    }

    @Override
    public EmployeeVO get(Long id) {
        UserBriefDTO u = requireUser(id, "无权查看该员工");
        return enrich(List.of(u)).get(0);
    }

    /**
     * 新增员工（按数据范围强制归属；密码为空回退默认密码、账号存在性校验均在用户中心完成）
     *
     * @param dto 员工保存入参
     */
    @Override
    public void create(EmployeeSaveDTO dto) {
        // 数据范围：院长强制本院、主任强制本科室（医院由科室推导），均忽略前端越权传参
        DataScope scope = dataScopeResolver.resolve();
        if (scope.isHospital()) {
            dto.setHospitalId(scope.getHospitalId());
        } else if (scope.isDepartment()) {
            dto.setDepartmentId(scope.getDepartmentId());
            dto.setHospitalId(scope.getHospitalId());
        }
        // 角色授予白名单：按当前登录人角色校验，越权授予在调用用户中心前拦截
        if (dto.getRoleIds() != null && !dto.getRoleIds().isEmpty()) {
            assertRoleGrantable(dto.getRoleIds(), loadRoleCodeMap());
        }
        unwrap(userClient.create(dto), "新增员工失败");
    }

    /**
     * 更新员工（按数据范围收敛归属），角色绑定由用户中心重建
     *
     * @param id 员工 ID
     * @param dto 员工更新入参
     */
    @Override
    public void update(Long id, EmployeeUpdateDTO dto) {
        UserBriefDTO target = requireUser(id, "无权操作该员工");
        List<Long> roleIds = dto.getRoleIds();
        if (roleIds != null) {
            Map<Long, String> codeById = loadRoleCodeMap();
            List<String> operatorRoles = operatorRoles();
            boolean selfUnchanged = id.equals(SecurityUtils.getCurrentUserIdOrNull())
                    && asSet(roleIds).equals(asSet(target.getRoleIds()));
            if (!operatorRoles.contains(RoleConstants.ADMIN)) {
                // 管理员目标保护：非 admin 不得改动管理员现有角色（防降级/剥离）
                if (codesOf(target.getRoleIds(), codeById).contains(RoleConstants.ADMIN)) {
                    throw new BusinessException("无权修改管理员角色");
                }
                // 自提权拦截：非 admin 仅可提交与现状一致的角色集合，实际变更一律拒绝
                if (id.equals(SecurityUtils.getCurrentUserIdOrNull()) && !selfUnchanged) {
                    throw new BusinessException("不能修改自己的角色");
                }
            }
            // 本人提交未变更的角色集合视为回显，不做授予白名单校验
            if (!selfUnchanged) {
                assertRoleGrantable(roleIds, codeById);
            }
        }
        DataScope scope = dataScopeResolver.resolve();
        if (scope.isHospital()) {
            // 院长：锁定本院，科室可调整（科室须属本院，前端越权传参无效）
            dto.setHospitalId(scope.getHospitalId());
        } else if (scope.isDepartment()) {
            // 科室主任不可将员工移出本科室
            dto.setDepartmentId(scope.getDepartmentId());
            dto.setHospitalId(scope.getHospitalId());
        }
        unwrap(userClient.update(id, dto), "更新员工失败");
    }

    @Override
    public void delete(Long id) {
        requireUser(id, "无权操作该员工");
        unwrap(userClient.delete(id), "删除员工失败");
    }

    /**
     * 变更员工启用状态（需在数据范围内）
     *
     * @param id 员工 ID
     * @param status 状态值（1 启用 / 0 禁用）
     */
    @Override
    public void changeStatus(Long id, Integer status) {
        StatusValidator.checkEnabledDisabled(status, "非法的状态值");
        UserBriefDTO u = requireUser(id, "无权操作该员工");
        EmployeeUpdateDTO req = new EmployeeUpdateDTO();
        req.setName(u.getName());
        req.setPhone(u.getPhone());
        req.setEmail(u.getEmail());
        req.setStatus(status);
        // 角色与绑定字段不传：用户中心仅在显式传入时重建角色、非空才覆盖绑定
        unwrap(userClient.update(id, req), "变更员工状态失败");
    }

    /**
     * 重置员工密码（为空回退默认密码，需在数据范围内）
     *
     * @param id 员工 ID
     * @param newPassword 新密码明文（可空）
     */
    @Override
    public void resetPassword(Long id, String newPassword) {
        UserBriefDTO u = requireUser(id, "无权操作该员工");
        EmployeeUpdateDTO req = new EmployeeUpdateDTO();
        req.setName(u.getName());
        req.setPhone(u.getPhone());
        req.setEmail(u.getEmail());
        req.setStatus(u.getStatus());
        // 空密码回退默认密码（用户中心 encodeOrDefault），角色与绑定字段不传避免误清
        req.setPassword(newPassword == null ? "" : newPassword);
        unwrap(userClient.update(id, req), "重置密码失败");
    }

    /**
     * 本人范围分页：仅返回当前登录人（关键字不匹配时返回空）
     *
     * @param query 分页与筛选条件
     * @param scope 本人范围
     * @return 员工分页结果
     */
    private PageResult<EmployeeVO> pageSelf(EmployeeQueryDTO query, DataScope scope) {
        Result<List<UserBriefDTO>> result = userClient.batch(List.of(scope.getUserId()));
        List<UserBriefDTO> rows = unwrap(result, "查询员工列表失败");
        List<UserBriefDTO> list = rows;
        if (StringUtils.hasText(query.getKeyword()) && !rows.isEmpty()) {
            UserBriefDTO u = rows.get(0);
            String kw = query.getKeyword().trim().toLowerCase();
            boolean match = (u.getUsername() != null && u.getUsername().toLowerCase().contains(kw))
                    || (u.getName() != null && u.getName().toLowerCase().contains(kw))
                    || (u.getPhone() != null && u.getPhone().contains(kw));
            list = match ? rows : List.of();
        }
        return PageResult.of(list.size(), query.getPageNum(), query.getPageSize(), enrich(list));
    }

    /**
     * 校验当前登录人可授予目标角色（角色 ID 经用户中心角色列表映射为角色码）
     * <p>
     * 未登录或无角色时按不可授予处理；角色 ID 不存在时直接拒绝，避免静默忽略。
     *
     * @param roleIds 目标角色 ID 列表（空表示不调整角色）
     * @param codeById 角色 ID→角色编码映射
     */
    private void assertRoleGrantable(List<Long> roleIds, Map<Long, String> codeById) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<String> targetCodes = roleIds.stream().distinct().map(roleId -> {
            String code = codeById.get(roleId);
            if (code == null) {
                throw new BusinessException("角色不存在：" + roleId);
            }
            return code;
        }).toList();
        RoleGrantPolicy.assertGrantable(operatorRoles(), targetCodes);
    }

    /**
     * 加载全量角色 ID→角色编码映射（用户中心角色下拉）
     *
     * @return 角色 ID→角色编码映射
     */
    private Map<Long, String> loadRoleCodeMap() {
        List<RoleBriefDTO> allRoles = unwrap(userClient.roles(), "查询角色列表失败");
        if (allRoles == null) {
            return Map.of();
        }
        return allRoles.stream()
                .collect(Collectors.toMap(RoleBriefDTO::getId, RoleBriefDTO::getRoleCode,
                        (first, second) -> first));
    }

    /**
     * 把角色 ID 映射为角色编码（不存在/已删除的角色 ID 忽略，用于读取现状）
     *
     * @param roleIds 角色 ID 列表
     * @param codeById 角色 ID→角色编码映射
     * @return 角色编码列表（无匹配返回空列表）
     */
    private static List<String> codesOf(List<Long> roleIds, Map<Long, String> codeById) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return roleIds.stream()
                .map(codeById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 当前登录人角色编码（未登录/无角色返回空列表）
     *
     * @return 角色编码列表
     */
    private static List<String> operatorRoles() {
        UserSnapshot operator = SecurityUtils.currentSnapshot();
        return operator == null || operator.getRoles() == null ? List.of() : operator.getRoles();
    }

    /**
     * 角色 ID 集合化（用于按集合比较角色是否有实际变化）
     *
     * @param roleIds 角色 ID 列表（可空）
     * @return 角色 ID 集合
     */
    private static Set<Long> asSet(List<Long> roleIds) {
        return roleIds == null ? Set.of() : new HashSet<>(roleIds);
    }

    /**
     * 查询员工并做范围校验（不存在/越权抛业务异常）
     *
     * @param id 员工 ID
     * @param failMessage 越权提示文案
     * @return 员工简要信息
     */
    private UserBriefDTO requireUser(Long id, String failMessage) {
        Result<List<UserBriefDTO>> result = userClient.batch(List.of(id));
        List<UserBriefDTO> list = unwrap(result, "查询员工失败");
        if (list.isEmpty()) {
            throw new BusinessException("员工不存在");
        }
        UserBriefDTO u = list.get(0);
        dataScopeResolver.resolve().assertCanAccess(u.getHospitalId(), u.getDepartmentId(), u.getId(), failMessage);
        return u;
    }

    /**
     * 批量回填医院名与科室名（本库 mapper 查询）
     *
     * @param list 员工简要列表
     * @return 员工视图列表
     */
    private List<EmployeeVO> enrich(List<UserBriefDTO> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> hospitalNames = namesByIds(
                list.stream().map(UserBriefDTO::getHospitalId).filter(Objects::nonNull).distinct().toList(),
                hospitalMapper::selectBatchIds, Hospital::getId, Hospital::getName);
        Map<Long, String> deptNames = namesByIds(
                list.stream().map(UserBriefDTO::getDepartmentId).filter(Objects::nonNull).distinct().toList(),
                departmentMapper::selectBatchIds, Department::getId, Department::getName);
        return list.stream().map(u -> {
            EmployeeVO vo = new EmployeeVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setName(u.getName());
            vo.setPhone(u.getPhone());
            vo.setEmail(u.getEmail());
            vo.setStatus(u.getStatus());
            vo.setRoleIds(u.getRoleIds());
            vo.setRoleNames(u.getRoleNames());
            vo.setHospitalId(u.getHospitalId());
            vo.setHospitalName(u.getHospitalId() == null ? null : hospitalNames.get(u.getHospitalId()));
            vo.setDepartmentId(u.getDepartmentId());
            vo.setDepartmentName(u.getDepartmentId() == null ? null : deptNames.get(u.getDepartmentId()));
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 按 ID 集合批量查询 ID→名称 映射（跳过空集合，重复 ID 取首个）
     *
     * @param ids ID 集合（已去空去重）
     * @param batchLoader 批量加载函数
     * @param idGetter 实体 ID 取值函数
     * @param nameGetter 实体名称取值函数
     * @param <T> 实体类型
     * @return ID→名称 映射（无数据返回空 Map）
     */
    private <T> Map<Long, String> namesByIds(List<Long> ids, Function<Collection<Long>, List<T>> batchLoader,
            Function<T, Long> idGetter, Function<T, String> nameGetter) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return batchLoader.apply(ids).stream()
                .collect(Collectors.toMap(idGetter, nameGetter, (first, second) -> first));
    }

    /**
     * 解包用户中心返回结果（非 200 抛业务异常，文案透传）
     *
     * @param result 用户中心返回
     * @param failMessage 失败兜底文案
     * @param <T> 数据类型
     * @return 业务数据
     */
    private <T> T unwrap(Result<T> result, String failMessage) {
        if (result == null || result.getCode() != 200) {
            throw new BusinessException(result == null ? failMessage : result.getMsg());
        }
        return result.getData();
    }
}
