package com.aiassistant.job;

import com.aiassistant.service.PendingActionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 待确认操作超时清理：每分钟把超过有效期仍停留在 PENDING 的记录置为 EXPIRED
 *
 * @author yh
 * @date 2026-11-05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingActionExpireScheduler {

    private final PendingActionService pendingActionService;

    @Scheduled(fixedDelay = 60000)
    public void expireOverdue() {
        int expired = pendingActionService.expireOverdue();
        if (expired > 0) {
            log.info("超时待确认操作已清理 {} 条", expired);
        }
    }
}
