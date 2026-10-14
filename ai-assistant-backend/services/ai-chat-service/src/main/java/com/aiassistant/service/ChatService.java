package com.aiassistant.service;

import com.aiassistant.dto.ChatRequest;
import com.aiassistant.dto.ChatResponse;
import com.aiassistant.dto.ModelInfo;
import com.aiassistant.dto.RoleInfo;
import com.aiassistant.dto.SessionDTO;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天服务接口。
 *
 * @author AI Assistant
 */
public interface ChatService {

    /**
     * 发送消息并获取 AI 回复（非流式，兼容旧接口）。
     *
     * @param request 聊天请求
     * @return 完整聊天响应
     */
    ChatResponse chat(ChatRequest request);

    /**
     * 流式聊天：通过 SSE 逐 token 推送回复。
     * <p>
     * 直接返回 {@code Flux<ServerSentEvent<String>>}，由 Spring MVC 适配为 HTTP SSE 流，
     * 无需手动管理 SseEmitter；阻塞型 DB 操作已在内部切换到弹性线程池执行。
     *
     * @param request 聊天请求
     * @return SSE 事件流
     */
    Flux<ServerSentEvent<String>> chatStream(ChatRequest request);

    /**
     * 获取可选角色列表（来自配置 ai.assistant.roles）。
     *
     * @return 角色视图列表
     */
    List<RoleInfo> listRoles();

    /**
     * 获取可选模型列表（来自配置 ai.assistant.models，含可用状态）。
     *
     * @return 模型视图列表
     */
    List<ModelInfo> listModels();

    /**
     * 获取指定会话的消息历史。
     *
     * @param sessionId 会话ID
     * @return 聊天响应（含历史消息）
     */
    ChatResponse getHistory(String sessionId);

    /**
     * 获取所有会话列表（按更新时间倒序）。
     *
     * @return 会话列表
     */
    List<SessionDTO> getSessionList();

    /**
     * 删除指定会话及其消息。
     *
     * @param sessionId 会话ID
     */
    void deleteSession(String sessionId);

    /**
     * 新建会话。
     *
     * @param title 会话标题
     * @return 聊天响应（含新建会话ID）
     */
    ChatResponse createSession(String title);
}
