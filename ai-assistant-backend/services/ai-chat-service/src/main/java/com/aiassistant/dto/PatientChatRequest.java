package com.aiassistant.dto;

import lombok.Data;

/**
 * 患者聊天请求
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
public class PatientChatRequest {

    /** 会话ID，为空则新建会话 */
    private String sessionId;

    /** 用户消息内容 */
    private String message;

    /** 会话类型：CHAT=闲聊 / TRIAGE=导诊 / RAG=知识库（新建会话时生效，缺省 CHAT） */
    private String sessionType;
}
