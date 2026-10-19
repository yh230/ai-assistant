package com.aiassistant.safety;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式安全守卫单元测试：跨 token 命中、归一化绕过、holdback 尾部放行、命中后标准文案仅下发一次
 *
 * @author yh
 * @date 2026-10-19
 */
class StreamingSafetyGuardTest {

    @Test
    void blocksKeywordSplitAcrossTokens() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("我", "突然胸", "痛"));
        List<String> tokens = guarded.tokens().collectList().block();
        String output = String.join("", tokens);
        // 命中前可能已放行若干前缀字符（holdback 之外的原始字符），因此只断言：
        // 输出以标准文案收尾、关键词未被完整放行、blocked 为 true
        assertTrue(guarded.blocked().get());
        assertTrue(output.endsWith("请立即就医"));
        assertFalse(output.contains("胸痛"));
        // 命中后 delivered 被重置为标准文案，不再保留已放行前缀
        assertEquals("请立即就医", guarded.delivered().toString());
    }

    @Test
    void passesSafeTextIncludingHeldBackTail() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("建议", "多休息", "多喝水"));
        assertEquals("建议多休息多喝水",
                String.join("", guarded.tokens().collectList().block()));
        assertFalse(guarded.blocked().get());
        assertEquals("建议多休息多喝水", guarded.delivered().toString());
    }

    @Test
    void blockedMessageIsNotRescanned() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("用药建议"), "不能提供用药建议");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("用药建议"));
        assertEquals(List.of("不能提供用药建议"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
    }

    @Test
    void blockedMessageIsNotRescannedAcrossTokens() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("用药建议"), "不能提供用药建议");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("用", "药", "建", "议"));
        assertEquals(List.of("不能提供用药建议"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
    }

    @Test
    void blocksKeywordHitInHeldBackTail() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("用药建议"), "不能提供用药建议");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("请用", "药建议"));
        assertEquals(List.of("不能提供用药建议"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
        assertEquals("不能提供用药建议", guarded.delivered().toString());
    }

    @Test
    void blocksKeywordWithFullWidthSpaceAcrossTokens() {
        // 全角空格（U+3000）拆分关键词，归一后仍应命中
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("用药建议"), "不能提供用药建议");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("用", "　", "药", "建", "议"));
        assertEquals(List.of("不能提供用药建议"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
    }

    @Test
    void blocksKeywordSplitBySpacesInSingleToken() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("用药建议"), "不能提供用药建议");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("请", "用　药", " 建议"));
        assertEquals(List.of("不能提供用药建议"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
    }

    @Test
    void blocksKeywordWithSpacesAroundEachCharacter() {
        // "胸 痛" 归一后为 "胸痛"，holdback 需覆盖被移除的空格
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("突 发 胸", " 痛"));
        List<String> tokens = guarded.tokens().collectList().block();
        String output = String.join("", tokens);
        // 首个 token 中 holdback 之外的字符（"突"）会先放行，命中后补发标准文案
        assertTrue(guarded.blocked().get());
        assertTrue(output.endsWith("请立即就医"));
        // 归一后关键词不得被完整放行（空格/全角空格被移除后仍不能出现 "胸痛"）
        assertFalse(output.replace(" ", "").replace("\u3000", "").contains("胸痛"));
        assertEquals("请立即就医", guarded.delivered().toString());
    }

    @Test
    void truncatesAlreadyDeliveredTextOnBlock() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医", 2);
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("建议您先休息", "胸痛"));
        assertEquals(List.of("建议", "请立即就医"), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
        assertEquals("请立即就医", guarded.delivered().toString());
    }

    @Test
    void doesNotDropWhitespaceOnlyTokens() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("你好", " ", "世界"));
        assertEquals("你好 世界", String.join("", guarded.tokens().collectList().block()));
        assertFalse(guarded.blocked().get());
        assertEquals("你好 世界", guarded.delivered().toString());
    }

    @Test
    void passesSafeTailWithFullWidthSpaces() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("建议", "多　休息"));
        assertEquals("建议多　休息", String.join("", guarded.tokens().collectList().block()));
        assertFalse(guarded.blocked().get());
    }

    @Test
    void passesAllTokensWhenKeywordListEmpty() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of(), "请立即就医");
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("你好", "，胸痛吗"));
        assertEquals("你好，胸痛吗", String.join("", guarded.tokens().collectList().block()));
        assertFalse(guarded.blocked().get());
    }

    @Test
    void emitsEmptyStringWhenBlockedMessageIsNull() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), null);
        StreamingSafetyGuard.Guarded guarded = guard.guard(Flux.just("胸痛"));
        assertEquals(List.of(""), guarded.tokens().collectList().block());
        assertTrue(guarded.blocked().get());
        assertEquals("", guarded.delivered().toString());
    }

    @Test
    void propagatesNonSafetyErrors() {
        StreamingSafetyGuard guard = new StreamingSafetyGuard(List.of("胸痛"), "请立即就医");
        StreamingSafetyGuard.Guarded guarded = guard.guard(
                Flux.just("建议").concatWith(Flux.error(new IllegalStateException("模型超时"))));
        assertThrows(IllegalStateException.class, () -> guarded.tokens().collectList().block());
        assertFalse(guarded.blocked().get());
    }
}
