package com.aiassistant.safety;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式安全守卫单元测试：跨 token 命中、holdback 尾部放行、命中后标准文案仅下发一次
 *
 * @author yh
 * @date 2026-10-19
 */
class StreamingSafetyGuardTest {

    @Test
    void blocksKeywordSplitAcrossTokens() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医", 10);
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("我", "突然胸", "痛"));
        assertEquals(List.of("请立即就医"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
        assertEquals("请立即就医", guarded.delivered().toString());
    }

    @Test
    void passesSafeTextIncludingHeldBackTail() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医", 10);
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("建议", "多休息", "多喝水"));
        assertEquals("建议多休息多喝水",
                String.join("", guarded.tokens().collectList().block()));
        assertFalse(guarded.blocked().get());
        assertEquals("建议多休息多喝水", guarded.delivered().toString());
    }

    @Test
    void blockedMessageIsNotRescanned() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("用药建议"), "不能提供用药建议", 10);
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("用药建议"));
        assertEquals(List.of("不能提供用药建议"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
    }

    @Test
    void blocksKeywordHitInHeldBackTail() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("用药建议"), "不能提供用药建议", 10);
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("请用", "药建议"));
        assertEquals(List.of("不能提供用药建议"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
        assertEquals("不能提供用药建议", guarded.delivered().toString());
    }

    @Test
    void truncatesAlreadyDeliveredTextOnBlock() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医", 2);
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("建议", "胸痛"));
        assertEquals(List.of("建", "请立即就医"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
        assertEquals("请立即就医", guarded.delivered().toString());
    }

    @Test
    void passesAllTokensWhenKeywordListEmpty() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of(), "请立即就医", 4);
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("你好", "，胸痛吗"));
        assertEquals("你好，胸痛吗", String.join("", guarded.tokens().collectList().block()));
        assertFalse(guarded.blocked().get());
    }

    @Test
    void propagatesNonSafetyErrors() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医", 10);
        StreamingSafetyGuard.Guarded guarded = guard.guard(
                Flux.just("建议").concatWith(Flux.error(new IllegalStateException("模型超时"))));
        assertThrows(IllegalStateException.class, () -> guarded.tokens().collectList().block());
        assertFalse(guarded.blocked().get());
    }
}
