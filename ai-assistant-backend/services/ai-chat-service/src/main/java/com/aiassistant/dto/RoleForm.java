package com.aiassistant.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 角色新增/编辑请求对象。
 * <p>
 * 用于「角色管理」页面的表单提交，与数据库实体 {@code Role} 解耦。
 *
 * @author AI Assistant
 */
@Data
public class RoleForm implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色ID（仅新增时必填，作为主键） */
    private String id;

    /** 角色名称 */
    private String name;

    /** 角色图标（emoji） */
    private String avatar;

    /** 角色简介 */
    private String description;

    /** 系统提示词 */
    private String systemPrompt;

    /** 展示排序 */
    private Integer sortOrder;

    /** 是否启用 */
    private Boolean enabled;
}
