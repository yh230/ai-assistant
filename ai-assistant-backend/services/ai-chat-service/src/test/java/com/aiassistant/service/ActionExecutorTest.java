package com.aiassistant.service;

import com.aiassistant.entity.PendingAction;
import com.api.client.RegistrationClient;
import com.api.dto.BookRequest;
import com.common.auth.UserSnapshot;
import com.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 写工具执行分发单元测试（业务错误透传 / Feign 异常固定文案 / 成功结果三类场景）
 *
 * @author yh
 * @date 2026-10-13
 */
@ExtendWith(MockitoExtension.class)
class ActionExecutorTest {

    @Mock private RegistrationClient registrationClient;

    private ActionExecutor executor;

    private final UserSnapshot user = UserSnapshot.builder()
            .userId(1L).username("13800000001").name("张三").build();

    @BeforeEach
    void setUp() {
        executor = new ActionExecutor(registrationClient, new ObjectMapper());
    }

    private PendingAction bookAction() {
        PendingAction action = new PendingAction();
        action.setToolName("bookAppointment");
        action.setToolArgs("{\"doctorUserId\":10,\"scheduleDate\":\"2026-10-15\",\"period\":1}");
        return action;
    }

    @Test
    void bookBusinessErrorMsgPassedThrough() {
        when(registrationClient.book(any(BookRequest.class)))
                .thenReturn(Result.fail(500, "该时段号源已满"));
        assertEquals("该时段号源已满", executor.execute(bookAction(), user));
    }

    @Test
    void bookFeignExceptionReturnsFixedText() {
        when(registrationClient.book(any(BookRequest.class)))
                .thenThrow(new RuntimeException("connect timed out"));
        assertEquals("挂号服务暂时不可用，请稍后重试", executor.execute(bookAction(), user));
    }

    @Test
    void bookSuccessReturnsConfirmText() {
        when(registrationClient.book(any(BookRequest.class))).thenReturn(Result.success(88L));
        String text = executor.execute(bookAction(), user);
        assertEquals("预约成功，挂号记录ID：88", text);
    }
}
