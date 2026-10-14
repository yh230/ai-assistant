package com.aiassistant.service;

import com.aiassistant.entity.PendingAction;
import com.aiassistant.repository.PendingActionRepository;
import com.aiassistant.service.impl.PendingActionServiceImpl;
import com.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 待确认操作状态机单元测试（纯 Mockito，覆盖缺失/归属/过期/重复/成功/并发防重放六场景）
 *
 * @author yh
 * @date 2026-10-13
 */
@ExtendWith(MockitoExtension.class)
class PendingActionServiceImplTest {

    @Mock private PendingActionRepository pendingActionRepository;
    @InjectMocks private PendingActionServiceImpl service;

    private PendingAction pending(String status, LocalDateTime expireTime) {
        PendingAction action = new PendingAction();
        action.setActionId("act-1");
        action.setSessionId("s-1");
        action.setUserId(1L);
        action.setIdentity("patient");
        action.setToolName("bookAppointment");
        action.setToolArgs("{}");
        action.setSummaryText("摘要");
        action.setStatus(status);
        action.setExpireTime(expireTime);
        return action;
    }

    @Test
    void confirmRejectsMissingAction() {
        when(pendingActionRepository.findByActionId("act-1")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> service.confirm("act-1", 1L, "patient"));
    }

    @Test
    void confirmRejectsWrongOwner() {
        when(pendingActionRepository.findByActionId("act-1"))
                .thenReturn(Optional.of(pending("PENDING", LocalDateTime.now().plusMinutes(3))));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm("act-1", 2L, "patient"));
        assertEquals("无权操作该确认请求", ex.getMessage());
    }

    @Test
    void confirmRejectsExpiredAndMarksExpired() {
        PendingAction action = pending("PENDING", LocalDateTime.now().minusSeconds(1));
        when(pendingActionRepository.findByActionId("act-1")).thenReturn(Optional.of(action));
        when(pendingActionRepository.markStatus(eq("act-1"), eq("EXPIRED"), any())).thenReturn(1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm("act-1", 1L, "patient"));
        assertEquals("确认已超时，请重新操作", ex.getMessage());
        assertEquals("EXPIRED", action.getStatus());
    }

    @Test
    void confirmRejectsAlreadyHandled() {
        when(pendingActionRepository.findByActionId("act-1"))
                .thenReturn(Optional.of(pending("CONFIRMED", LocalDateTime.now().plusMinutes(3))));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm("act-1", 1L, "patient"));
        assertEquals("该操作已处理", ex.getMessage());
    }

    @Test
    void confirmMarksConfirmed() {
        PendingAction action = pending("PENDING", LocalDateTime.now().plusMinutes(3));
        when(pendingActionRepository.findByActionId("act-1")).thenReturn(Optional.of(action));
        when(pendingActionRepository.markStatus(eq("act-1"), eq("CONFIRMED"), any())).thenReturn(1);
        PendingAction result = service.confirm("act-1", 1L, "patient");
        assertEquals("CONFIRMED", result.getStatus());
        assertNotNull(result.getConfirmTime());
    }

    @Test
    void confirmRejectsConcurrentReplayWhenConditionalUpdateMisses() {
        when(pendingActionRepository.findByActionId("act-1"))
                .thenReturn(Optional.of(pending("PENDING", LocalDateTime.now().plusMinutes(3))));
        // 条件更新影响行数为 0：记录已被并发请求流转，防重放拒绝
        when(pendingActionRepository.markStatus(eq("act-1"), eq("CONFIRMED"), any())).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm("act-1", 1L, "patient"));
        assertEquals("该操作已处理", ex.getMessage());
    }
}
