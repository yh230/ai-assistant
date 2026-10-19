package com.aiassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Size;

/**
 * 聊天请求 DTO。
 *
 * @author AI Assistant
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 会话ID，为空则新建会话 */
    private String sessionId;

    /** 用户消息内容 */
    @Size(max = 4000, message = "消息长度不能超过4000字")
    private String message;

    /** 会话标题（新建会话时使用，可选） */
    private String title;

    /** 角色ID（数据库 ai_role 表，员工域）；为空使用默认角色 */
    private String roleId;

    /** 模型ID，对应配置 ai.assistant.models；为空使用默认模型 */
    private String model;
}
