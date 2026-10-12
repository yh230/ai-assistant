package com.aiassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色视图对象（VO），返回给前端用于角色选择展示。
 *
 * @author AI Assistant
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleInfo {

    /** 角色ID，对应配置 ai.assistant.roles 的 key */
    private String id;

    /** 角色名称，如「聊天伙伴」 */
    private String name;

    /** 角色图标（emoji） */
    private String avatar;

    /** 角色描述 */
    private String description;
}
