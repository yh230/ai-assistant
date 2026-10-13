package com.aiassistant.service;

import com.aiassistant.dto.RoleForm;
import com.aiassistant.entity.Role;
import com.aiassistant.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    /** 角色ID格式：字母、数字、下划线，2~32 位 */
    private static final Pattern ROLE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_]{2,32}$");

    /** 兜底默认角色ID（仅当数据库完全为空且无内置角色时使用） */
    private static final String DEFAULT_ROLE_ID = "chat_buddy";

    /** 角色缺失时的兜底系统提示词 */
    private static final String DEFAULT_SYSTEM_PROMPT = "你是用户的 AI 助手，请友好、简洁、真诚地回应。";

    private final RoleRepository roleRepository;

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
        return roleRepository.findByEnabledTrueOrderBySortOrderAscIdAsc();
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
        List<Role> enabled = roleRepository.findByEnabledTrueOrderBySortOrderAscIdAsc();
        if (!enabled.isEmpty()) {
            return enabled.get(0).getId();
        }
        List<Role> all = roleRepository.findAllByOrderBySortOrderAscIdAsc();
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
     * 判断指定角色是否存在且已启用。
     */
    private boolean isEnabled(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return roleRepository.findById(roleId.trim())
                .map(r -> Boolean.TRUE.equals(r.getEnabled()))
                .orElse(false);
    }

    /**
     * 将表单转换为实体（id 由参数指定，避免表单内 id 覆盖路径参数）。
     */
    private Role toEntity(RoleForm form, String id) {
        return Role.builder()
                .id(id)
                .name(form.getName().trim())
                .avatar(form.getAvatar())
                .description(form.getDescription())
                .systemPrompt(form.getSystemPrompt())
                .sortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder())
                .enabled(form.getEnabled() == null ? Boolean.TRUE : form.getEnabled())
                .build();
    }
}
