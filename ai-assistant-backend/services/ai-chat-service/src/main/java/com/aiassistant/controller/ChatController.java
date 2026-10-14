package com.aiassistant.controller;

import com.common.result.Result;
import com.common.security.operlog.OperLog;
import com.aiassistant.dto.ChatRequest;
import com.aiassistant.dto.ChatResponse;
import com.aiassistant.dto.ModelInfo;
import com.aiassistant.dto.RoleInfo;
import com.aiassistant.dto.SessionDTO;
import com.aiassistant.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天接口
 * <p>
 * 流式端点直接返回 {@code Flux<ServerSentEvent<String>>}，由 Spring MVC 适配为 HTTP SSE 流。
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin
public class ChatController {

    private final ChatService chatService;

    /**
     * 发送消息并获取 AI 回复（非流式，兼容旧接口）
     * POST /api/chat/send
     *
     * @param request 聊天请求体
     * @return 聊天响应
     */
    @PostMapping("/send")
    public Result<ChatResponse> sendMessage(@RequestBody ChatRequest request) {
        log.info("收到聊天消息: sessionId={}, roleId={}, model={}", request.getSessionId(), request.getRoleId(), request.getModel());
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return Result.fail(400, "消息内容不能为空");
        }
        return Result.success(chatService.chat(request));
    }

    /**
     * 流式聊天：SSE 逐 token 推送。
     * POST /api/chat/stream
     * <p>
     * 事件格式：
     * <pre>
     *   event:session  data:{"sessionId":"xxx","title":"xxx"}
     *   event:token    data:逐个文字片段
     *   event:done     data:{"localFallback":false}
     *   event:error    data:{"message":"xxx"}
     * </pre>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        log.info("收到流式聊天消息: roleId={}, model={}", request.getRoleId(), request.getModel());
        return chatService.chatStream(request);
    }

    /**
     * 获取可选角色列表
     * GET /api/chat/roles
     *
     * @return 已启用角色列表
     */
    @GetMapping("/roles")
    public Result<List<RoleInfo>> getRoles() {
        return Result.success(chatService.listRoles());
    }

    /**
     * 获取可选模型列表（含可用状态）
     * GET /api/chat/models
     *
     * @return 模型信息列表
     */
    @GetMapping("/models")
    public Result<List<ModelInfo>> getModels() {
        return Result.success(chatService.listModels());
    }

    /**
     * 获取会话消息历史
     * GET /api/chat/history/{sessionId}
     *
     * @param sessionId 会话标识
     * @return 会话历史（含消息列表）
     */
    @GetMapping("/history/{sessionId}")
    public Result<ChatResponse> getHistory(@PathVariable String sessionId) {
        return Result.success(chatService.getHistory(sessionId));
    }

    /**
     * 获取所有会话列表
     * GET /api/chat/sessions
     *
     * @return 会话列表
     */
    @GetMapping("/sessions")
    public Result<List<SessionDTO>> getSessions() {
        return Result.success(chatService.getSessionList());
    }

    /**
     * 新建会话
     * POST /api/chat/session?title=标题
     *
     * @param title 会话标题（缺省为「新对话」）
     * @return 新建的会话信息
     */
    @PostMapping("/session")
    public Result<ChatResponse> createSession(@RequestParam(required = false, defaultValue = "新对话") String title) {
        return Result.success(chatService.createSession(title));
    }

    /**
     * 删除会话
     * DELETE /api/chat/session/{sessionId}
     *
     * @param sessionId 会话标识
     * @return 操作结果
     */
    @OperLog(module = "AI 对话", action = "删除会话")
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        chatService.deleteSession(sessionId);
        return Result.success();
    }
}
