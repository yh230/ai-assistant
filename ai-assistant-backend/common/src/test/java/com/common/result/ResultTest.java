package com.common.result;

import com.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 统一返回结构解包测试
 *
 * @author yh
 * @date 2026-10-21
 */
class ResultTest {

    @Test
    void unwrapReturnsDataOnSuccess() {
        assertEquals("payload", Result.success("payload").unwrap("失败兜底"));
    }

    @Test
    void unwrapReturnsNullDataOnSuccess() {
        assertNull(Result.success().unwrap("失败兜底"));
    }

    @Test
    void unwrapThrowsWithServerMessageWhenFail() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> Result.fail(500, "远端业务失败").unwrap("失败兜底"));
        assertEquals("远端业务失败", ex.getMessage());
    }

    @Test
    void unwrapFallsBackWhenMessageNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> Result.fail(500, null).unwrap("失败兜底"));
        assertEquals("失败兜底", ex.getMessage());
    }

    @Test
    void unwrapFallsBackWhenMessageBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> Result.fail(503, "   ").unwrap("失败兜底"));
        assertEquals("失败兜底", ex.getMessage());
    }
}
