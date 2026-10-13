package com.aiassistant.controller;

import com.aiassistant.dto.ChatResponse;
import com.aiassistant.dto.PatientChatRequest;
import com.aiassistant.dto.SessionDTO;
import com.aiassistant.service.PatientChatService;
import com.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 患者聊天接口（导诊 SSE 与会话管理，会话一律限定归属当前登录患者）
 * <p>
 * 流式端点直接返回 {@code Flux<ServerSentEvent<String>>}，由 Spring MVC 适配为 HTTP SSE 流，
 * 事件格式与员工侧一致：session/token/done/error。
 *
 * @author yh
 * @date 2026-10-13
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/patient")
@RequiredArgsConstructor
public class PatientChatController {

    private final PatientChatService patientChatService;

    /**
     * 患者流式对话（SSE 逐 token 推送，事件格式与员工侧一致：session/token/done/error）
     *
     * @param request 患者聊天请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody PatientChatRequest request) {
        log.info("收到患者流式聊天消息: sessionId={}, sessionType={}",
                request.getSessionId(), request.getSessionType());
        return patientChatService.stream(request);
    }

    /**
     * 患者本人会话列表
     *
     * @return 会话列表（仅当前登录患者）
     */
    @GetMapping("/sessions")
    public Result<List<SessionDTO>> sessions() {
        return Result.success(patientChatService.listSessions());
    }

    /**
     * 患者本人会话历史
     *
     * @param sessionId 会话ID
     * @return 聊天响应（含消息列表）
     */
    @GetMapping("/history/{sessionId}")
    public Result<ChatResponse> history(@PathVariable String sessionId) {
        return Result.success(patientChatService.getHistory(sessionId));
    }

    /**
     * 新建会话（type=CHAT/TRIAGE/RAG）
     *
     * @param type 会话类型（缺省 CHAT）
     * @return 含新会话ID的响应
     */
    @PostMapping("/session")
    public Result<ChatResponse> createSession(@RequestParam(defaultValue = "CHAT") String type) {
        return Result.success(patientChatService.createSession(type));
    }

    /**
     * 删除会话（校验归属）
     *
     * @param sessionId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        patientChatService.deleteSession(sessionId);
        return Result.success();
    }
}
