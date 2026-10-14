package com.aiassistant.service;

import com.aiassistant.dto.AdminSessionDTO;
import com.aiassistant.dto.ChatResponse;
import com.common.result.PageResult;

/**
 * 管理端患者会话查询服务（按操作者医院范围做数据隔离）
 *
 * @author yh
 * @date 2026-10-13
 */
public interface AdminChatService {

    /**
     * 分页查询患者会话（仅患者会话；按操作者医院范围过滤，平台管理员不额外过滤）
     *
     * @param sessionType 会话类型（可空=全部）
     * @param pageNum     页码（从 1 开始）
     * @param pageSize    每页大小
     * @return 患者会话分页数据（患者姓名/手机号批量回填）
     */
    PageResult<AdminSessionDTO> pageSessions(String sessionType, long pageNum, long pageSize);

    /**
     * 查看患者会话消息历史（校验医院边界）
     *
     * @param sessionId 会话标识
     * @return 会话消息历史（按时间正序）
     */
    ChatResponse getSessionHistory(String sessionId);
}
