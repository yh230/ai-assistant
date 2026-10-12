package com.aiassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型视图对象（VO），返回给前端用于模型选择展示。
 *
 * @author AI Assistant
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfo {

    /** 模型ID，对应配置 ai.assistant.models 的 key */
    private String id;

    /** 模型展示名称（实际模型名，如 deepseek-chat） */
    private String label;

    /** 是否已启用且凭据有效（可用） */
    private boolean active;
}
