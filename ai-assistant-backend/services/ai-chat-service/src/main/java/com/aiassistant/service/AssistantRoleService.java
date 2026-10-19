package com.aiassistant.service;

import com.aiassistant.config.AiProperties;
import com.aiassistant.dto.RoleForm;
import com.aiassistant.entity.Role;
import com.aiassistant.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 角色服务 - 数据库驱动（管理 AI 助手的人设/角色）。
 * <p>
 * 负责角色的解析（聊天时按 roleId 选取人设）、列表查询与增删改查。
 * 配置 {@code ai.assistant.default-role} 仅作为「找不到合适角色时」的兜底，
 * 角色主数据全部以数据库为准。
 * <p>
 * 注：此类由原 {@code RoleService} 重命名而来，以避免与 RBAC 模块中的
 * {@code com.aiassistant.system.service.RoleService}（权限角色服务）产生同名 Bean 冲突。
 *
 * @author AI Assistant
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantRoleService {

    /** 特征：患者角色域 */
    public static final String SCOPE_PATIENT = "PATIENT";
    /** 特征：员工角色域 */
    public static final String SCOPE_STAFF = "STAFF";

    /** 角色ID格式：字母、数字、下划线，2~32 位 */
    private static final Pattern ROLE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_]{2,32}$");

    /** 兜底默认角色ID（仅当数据库完全为空且无内置角色时使用） */
    private static final String DEFAULT_ROLE_ID = "chat_buddy";

    /** 角色缺失时的兜底系统提示词 */
    private static final String DEFAULT_SYSTEM_PROMPT = "你是用户的 AI 助手，请友好、简洁、真诚地回应。";

    private final RoleRepository roleRepository;
    private final AiProperties aiProperties;
    private final PlatformTransactionManager txManager;

    // ==================== 查询 ====================

    /**
     * 查询全部角色（含禁用），按排序号、ID 升序，供管理页面使用。
     *
     * @return 全量角色列表
     */
    @Transactional(readOnly = true)
    public List<Role> listAll() {
        return roleRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    /**
     * 查询已启用角色（按排序号、ID 升序），供聊天选择器使用。
     *
     * @return 已启用角色列表
     */
    @Transactional(readOnly = true)
    public List<Role> listEnabledOrdered() {
        return roleRepository.findByScopeAndEnabledTrueOrderBySortOrderAscIdAsc(SCOPE_STAFF);
    }

    /**
     * 按ID查询单个角色。
     *
     * @param id 角色ID
     * @return 角色（可能为空）
     */
    @Transactional(readOnly = true)
    public Optional<Role> findById(String id) {
        Assert.hasText(id, "角色ID不能为空");
        return roleRepository.findById(id);
    }

    // ==================== 聊天解析 ====================

    /**
     * 解析实际使用的角色ID。
     * <ul>
     *     <li>请求的角色存在且已启用 → 使用之；</li>
     *     <li>否则回退到默认角色（需启用）；</li>
     *     <li>否则回退到第一个已启用角色；</li>
     *     <li>否则回退到第一个角色；</li>
     *     <li>库空时回退到配置中的默认角色ID。</li>
     * </ul>
     *
     * @param requested 用户请求的角色ID（可为空）
     * @return 实际角色ID
     */
    @Transactional(readOnly = true)
    public String resolveRoleId(String requested) {
        if (isEnabled(requested)) {
            return requested;
        }
        List<Role> enabled = roleRepository.findByScopeAndEnabledTrueOrderBySortOrderAscIdAsc(SCOPE_STAFF);
        if (!enabled.isEmpty()) {
            return enabled.get(0).getId();
        }
        List<Role> all = roleRepository.findByScopeOrderBySortOrderAscIdAsc(SCOPE_STAFF);
        if (!all.isEmpty()) {
            return all.get(0).getId();
        }
        return DEFAULT_ROLE_ID;
    }

    /**
     * 获取用于对话的最终角色（已启用优先，回退逻辑同 {@link #resolveRoleId}）。
     *
     * @param roleId 请求角色ID（可为空）
     * @return 实际角色，库空且无配置时返回 null
     */
    @Transactional(readOnly = true)
    public Role getActiveOrDefault(String roleId) {
        String resolved = resolveRoleId(roleId);
        return roleRepository.findById(resolved).orElse(null);
    }

    /**
     * 解析角色的系统提示词（供聊天时拼接用户画像）。
     *
     * @param roleId 请求角色ID（可为空）
     * @return 系统提示词（永不为 null，缺失时返回兜底提示词）
     */
    @Transactional(readOnly = true)
    public String resolveSystemPrompt(String roleId) {
        Role role = getActiveOrDefault(roleId);
        if (role != null && role.getSystemPrompt() != null && !role.getSystemPrompt().isBlank()) {
            return role.getSystemPrompt();
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    // ==================== 患者角色（按医院库驱动） ====================

    /**
     * 解析患者角色（缺失则按模板惰性建库）
     * <p>
     * 建库走独立事务（{@code REQUIRES_NEW}），使唯一键冲突在方法内以异常形式抛出，
     * 从而能在本方法中捕获并重查，避免并发双建时首个请求直接失败。
     *
     * @param hospitalId 医院 ID（为空时不建库，返回 null）
     * @param sessionType 会话类型 CHAT/TRIAGE/RAG
     * @return 患者角色；医院为空时返回 null
     */
    public Role resolvePatientRole(Long hospitalId, String sessionType) {
        if (hospitalId == null) {
            return null;
        }
        String type = normalizeSessionType(sessionType);
        Optional<Role> existing = roleRepository
                .findByScopeAndHospitalIdAndSessionType(SCOPE_PATIENT, hospitalId, type);
        if (existing.isPresent()) {
            return existing.get();
        }
        Role role = Role.builder()
                .id("pat_" + hospitalId + "_" + type)
                .name(patientRoleName(type))
                .avatar(patientRoleAvatar(type))
                .description(patientRoleDescription(type))
                .systemPrompt(promptTemplate(type))
                .sortOrder(patientRoleSort(type))
                .enabled(Boolean.TRUE)
                .scope(SCOPE_PATIENT)
                .sessionType(type)
                .hospitalId(hospitalId)
                .build();
        try {
            return insertRoleInNewTransaction(role);
        } catch (DataIntegrityViolationException e) {
            // 并发双建：独立事务内的冲突已回滚，此处用新语句重查胜出者
            log.warn("患者角色并发创建冲突，重查: scope={}, hospitalId={}, sessionType={}",
                    SCOPE_PATIENT, hospitalId, type);
            return roleRepository.findByScopeAndHospitalIdAndSessionType(SCOPE_PATIENT, hospitalId, type)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 在独立事务中插入角色，使提交/失败都在调用点之前完成
     *
     * @param role 待插入角色
     * @return 持久化后的角色
     */
    private Role insertRoleInNewTransaction(Role role) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> roleRepository.save(role));
    }

    /**
     * 解析患者系统提示词（角色禁用或医院为空时回退模板）
     * <p>
     * 非事务方法：若包裹读事务，REPEATABLE READ 会固定读视图，冲突后的重查将看不到胜出行。
     *
     * @param hospitalId 医院 ID
     * @param sessionType 会话类型
     * @return 系统提示词
     */
    public String resolvePatientSystemPrompt(Long hospitalId, String sessionType) {
        Role role = resolvePatientRole(hospitalId, sessionType);
        if (role != null && Boolean.TRUE.equals(role.getEnabled())
                && role.getSystemPrompt() != null && !role.getSystemPrompt().isBlank()) {
            return role.getSystemPrompt();
        }
        return promptTemplate(normalizeSessionType(sessionType));
    }

    private String normalizeSessionType(String type) {
        String normalized = type == null ? "CHAT" : type.trim().toUpperCase();
        return switch (normalized) {
            case "TRIAGE", "RAG", "CHAT" -> normalized;
            default -> "CHAT";
        };
    }

    private String promptTemplate(String type) {
        return switch (type) {
            case "TRIAGE" -> aiProperties.getPrompt().getTriage();
            case "RAG" -> aiProperties.getPrompt().getRag();
            default -> aiProperties.getPrompt().getChat();
        };
    }

    private String patientRoleName(String type) {
        return switch (type) {
            case "TRIAGE" -> "AI 导诊";
            case "RAG" -> "知识库问答";
            default -> "患者闲聊";
        };
    }

    private String patientRoleAvatar(String type) {
        return switch (type) {
            case "TRIAGE" -> "🩺";
            case "RAG" -> "📖";
            default -> "💬";
        };
    }

    private String patientRoleDescription(String type) {
        return switch (type) {
            case "TRIAGE" -> "患者导诊人设（按医院维护）";
            case "RAG" -> "患者知识库问答人设（按医院维护）";
            default -> "患者闲聊人设（按医院维护）";
        };
    }

    private int patientRoleSort(String type) {
        return switch (type) {
            case "CHAT" -> 0;
            case "TRIAGE" -> 1;
            default -> 2;
        };
    }

    // ==================== 管理：按域查询与增改 ====================

    /**
     * 按角色域查询列表（患者域必须指定医院）
     *
     * @param scope 角色域 STAFF/PATIENT
     * @param hospitalId 医院 ID（患者域必填）
     * @return 角色列表
     */
    @Transactional(readOnly = true)
    public List<Role> listByScope(String scope, Long hospitalId) {
        if (SCOPE_PATIENT.equalsIgnoreCase(scope)) {
            if (hospitalId == null) {
                throw new IllegalArgumentException("查询患者角色必须指定医院");
            }
            return roleRepository.findByScopeAndHospitalIdOrderBySortOrderAscIdAsc(SCOPE_PATIENT, hospitalId);
        }
        return roleRepository.findByScopeOrderBySortOrderAscIdAsc(SCOPE_STAFF);
    }

    /**
     * 新增角色（患者角色强制写入医院与会话类型校验）
     *
     * @param form 角色表单
     * @param hospitalId 服务端确定的医院 ID（患者域必填）
     * @return 持久化后的角色
     */
    @Transactional
    public Role createForScope(RoleForm form, Long hospitalId) {
        boolean patient = SCOPE_PATIENT.equalsIgnoreCase(form.getScope());
        if (patient) {
            if (hospitalId == null) {
                throw new IllegalArgumentException("新增患者角色必须指定医院");
            }
            if (form.getSessionType() == null || form.getSessionType().isBlank()) {
                throw new IllegalArgumentException("患者角色必须指定会话类型");
            }
            form.setHospitalId(hospitalId);
            form.setSessionType(normalizeSessionType(form.getSessionType()));
        }
        return create(form);
    }

    /**
     * 更新角色（患者角色不允许改医院）
     *
     * @param id 角色ID（路径参数）
     * @param form 角色表单
     * @param hospitalId 服务端确定的医院 ID（未绑院管理员为空）
     * @return 更新后的角色
     */
    @Transactional
    public Role updateForScope(String id, RoleForm form, Long hospitalId) {
        Role existing = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在: " + id));
        if (SCOPE_PATIENT.equalsIgnoreCase(existing.getScope()) && hospitalId != null
                && !hospitalId.equals(existing.getHospitalId())) {
            throw new IllegalArgumentException("无权修改其他医院的患者角色");
        }
        return update(id, form);
    }

    /**
     * 按ID查询角色，并对患者角色做跨院校验
     *
     * @param id 角色ID
     * @param operatorHospitalId 操作人绑定医院（未绑院为 null，此时不做限制）
     * @return 角色（可能为空）
     */
    @Transactional(readOnly = true)
    public Optional<Role> findByIdForScope(String id, Long operatorHospitalId) {
        Optional<Role> role = roleRepository.findById(id);
        role.ifPresent(r -> assertPatientRoleAccessible(r, operatorHospitalId));
        return role;
    }

    /**
     * 删除角色，并对患者角色做跨院校验
     *
     * @param id 角色ID
     * @param operatorHospitalId 操作人绑定医院（未绑院为 null，此时不做限制）
     */
    @Transactional
    public void deleteForScope(String id, Long operatorHospitalId) {
        Role existing = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在: " + id));
        assertPatientRoleAccessible(existing, operatorHospitalId);
        roleRepository.delete(existing);
        log.info("删除角色: {}", id);
    }

    /**
     * 校验操作人是否有权操作该患者角色（仅患者域按医院隔离）
     *
     * @param role 目标角色
     * @param operatorHospitalId 操作人绑定医院（null 表示未绑院，不限制）
     */
    private void assertPatientRoleAccessible(Role role, Long operatorHospitalId) {
        if (role != null && SCOPE_PATIENT.equalsIgnoreCase(role.getScope())
                && operatorHospitalId != null
                && !operatorHospitalId.equals(role.getHospitalId())) {
            throw new IllegalArgumentException("无权操作其他医院的患者角色");
        }
    }

    // ==================== 管理：增删改 ====================

    /**
     * 新增角色。
     *
     * @param form 角色表单
     * @return 持久化后的角色
     */
    @Transactional
    public Role create(RoleForm form) {
        Assert.notNull(form, "角色表单不能为空");
        Assert.hasText(form.getId(), "角色ID不能为空");
        Assert.isTrue(ROLE_ID_PATTERN.matcher(form.getId().trim()).matches(),
                "角色ID仅允许字母、数字、下划线，长度 2~32");
        Assert.hasText(form.getName(), "角色名称不能为空");
        Assert.hasText(form.getSystemPrompt(), "系统提示词不能为空");
        Assert.isTrue(!roleRepository.existsById(form.getId().trim()), "角色ID已存在");

        Role role = toEntity(form, form.getId().trim());
        log.info("新增角色: {}", role.getId());
        return roleRepository.save(role);
    }

    /**
     * 更新角色（按路径中的 id，忽略表单内的 id）。
     *
     * @param id   角色ID（路径参数）
     * @param form 角色表单
     * @return 更新后的角色
     */
    @Transactional
    public Role update(String id, RoleForm form) {
        Assert.hasText(id, "角色ID不能为空");
        Role existing = roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在: " + id));

        if (form.getName() != null) {
            Assert.hasText(form.getName(), "角色名称不能为空");
            existing.setName(form.getName().trim());
        }
        if (form.getAvatar() != null) {
            existing.setAvatar(form.getAvatar());
        }
        if (form.getDescription() != null) {
            existing.setDescription(form.getDescription());
        }
        if (form.getSystemPrompt() != null) {
            Assert.hasText(form.getSystemPrompt(), "系统提示词不能为空");
            existing.setSystemPrompt(form.getSystemPrompt());
        }
        if (form.getSortOrder() != null) {
            existing.setSortOrder(form.getSortOrder());
        }
        if (form.getEnabled() != null) {
            existing.setEnabled(form.getEnabled());
        }
        log.info("更新角色: {}", id);
        return roleRepository.save(existing);
    }

    /**
     * 删除角色。
     *
     * @param id 角色ID
     */
    @Transactional
    public void delete(String id) {
        Assert.hasText(id, "角色ID不能为空");
        if (!roleRepository.existsById(id)) {
            throw new IllegalArgumentException("角色不存在: " + id);
        }
        roleRepository.deleteById(id);
        log.info("删除角色: {}", id);
    }

    // ==================== 私有辅助 ====================

    /**
     * 判断指定角色是否存在、已启用且属于员工角色域。
     */
    private boolean isEnabled(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return roleRepository.findById(roleId.trim())
                .map(r -> Boolean.TRUE.equals(r.getEnabled()) && SCOPE_STAFF.equals(r.getScope()))
                .orElse(false);
    }

    /**
     * 将表单转换为实体（id 由参数指定，避免表单内 id 覆盖路径参数）。
     */
    private Role toEntity(RoleForm form, String id) {
        boolean patient = SCOPE_PATIENT.equalsIgnoreCase(form.getScope());
        return Role.builder()
                .id(id)
                .name(form.getName().trim())
                .avatar(form.getAvatar())
                .description(form.getDescription())
                .systemPrompt(form.getSystemPrompt())
                .sortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder())
                .enabled(form.getEnabled() == null ? Boolean.TRUE : form.getEnabled())
                .scope(patient ? SCOPE_PATIENT : SCOPE_STAFF)
                .sessionType(patient ? form.getSessionType() : null)
                .hospitalId(patient ? form.getHospitalId() : null)
                .build();
    }
}
