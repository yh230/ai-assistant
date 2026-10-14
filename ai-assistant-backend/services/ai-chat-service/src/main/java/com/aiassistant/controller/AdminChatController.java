package com.aiassistant.controller;

import com.aiassistant.dto.AdminSessionDTO;
import com.aiassistant.dto.ChatResponse;
import com.aiassistant.service.AdminChatService;
import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.operlog.OperLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端患者会话接口
 *
 * @author yh
 * @date 2026-10-13
 */
@RestController
@RequestMapping("/api/chat/admin")
@RequiredArgsConstructor
public class AdminChatController {

    private final AdminChatService adminChatService;

    /**
     * 患者会话分页（仅患者会话；按操作者医院范围过滤，sessionType 可空=全部）
     *
     * @param sessionType 会话类型（可空=全部）
     * @param pageNum     页码（从 1 开始）
     * @param pageSize    每页大小
     * @return 患者会话分页数据
     */
    @GetMapping("/sessions")
    public Result<PageResult<AdminSessionDTO>> sessions(@RequestParam(required = false) String sessionType,
                                                        @RequestParam(defaultValue = "1") long pageNum,
                                                        @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(adminChatService.pageSessions(sessionType, pageNum, pageSize));
    }

    /**
     * 会话消息详情（校验医院范围）
     *
     * @param sessionId 会话标识
     * @return 会话消息历史
     */
    @OperLog(module = "患者会话", action = "调阅会话历史")
    @GetMapping("/sessions/{sessionId}/history")
    public Result<ChatResponse> history(@PathVariable String sessionId) {
        return Result.success(adminChatService.getSessionHistory(sessionId));
    }
}
