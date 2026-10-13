package com.aiassistant.service;

import com.aiassistant.dto.ChatResponse;
import com.aiassistant.dto.PatientChatRequest;
import com.aiassistant.dto.SessionDTO;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 患者聊天服务：会话一律限定归属当前登录患者（identity=patient），隔离口径与员工侧一致
 *
 * @author yh
 * @date 2026-10-13
 */
public interface PatientChatService {

    /**
     * 患者流式对话（SSE）
     *
     * @param request 患者聊天请求
     * @return SSE 事件流（session/token/done/error）
     */
    Flux<ServerSentEvent<String>> stream(PatientChatRequest request);

    /**
     * 患者会话列表（仅本人患者会话）
     *
     * @return 会话列表
     */
    List<SessionDTO> listSessions();

    /**
     * 患者会话历史（校验归属）
     *
     * @param sessionId 会话ID
     * @return 聊天响应（含消息列表）
     */
    ChatResponse getHistory(String sessionId);

    /**
     * 新建患者会话
     *
     * @param type 会话类型（CHAT/TRIAGE/RAG）
     * @return 含新会话ID的响应
     */
    ChatResponse createSession(String type);

    /**
     * 删除患者会话（校验归属）
     *
     * @param sessionId 会话ID
     */
    void deleteSession(String sessionId);
}
