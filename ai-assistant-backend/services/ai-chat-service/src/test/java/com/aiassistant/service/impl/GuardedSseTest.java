package com.aiassistant.service.impl;

import com.aiassistant.service.support.ChatPersistContent;
import com.common.constant.ChatConstants;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式风控 SSE 适配单测：证明守卫确实位于「token -> SSE / 正文累积」之前，
 * 跨 token 关键词不会出现在下发事件中，且落库正文改用标准文案
 *
 * @author yh
 * @date 2026-10-19
 */
class GuardedSseTest {

    private static final List<String> KEYWORDS = List.of("用药建议");

    private static final String BLOCKED_MESSAGE = "不能提供用药建议";

    private static final String DISCLAIMER = "以上内容仅供参考。";

    @Test
    void keywordSplitAcrossTokensNeverReachesSseAndPersistsStandardMessage() {
        StringBuffer full = new StringBuffer();
        AtomicBoolean blocked = new AtomicBoolean(false);

        List<ServerSentEvent<String>> events = GuardedSse.guardedSse(
                        Flux.just("建议您先", "用", "药", "建", "议", "后复查"),
                        full, blocked, KEYWORDS, BLOCKED_MESSAGE)
                .collectList()
                .block();

        String emitted = events.stream().map(ServerSentEvent::data).collect(Collectors.joining());
        // 关键词被拆到多个 token，守卫必须在映射为 SSE 前命中
        assertTrue(blocked.get());
        // 标准文案自身包含关键词（"不能提供用药建议"），因此不能断言输出不含关键词；
        // 应断言模型原始不安全内容（前缀与尾部）均未下发，且仅下发了标准文案
        assertEquals(BLOCKED_MESSAGE, emitted);
        assertFalse(emitted.contains("建议您先"));
        assertFalse(emitted.contains("后复查"));
        // 命中后累积器只保留标准文案（已放行前缀被守卫丢弃）
        assertEquals(BLOCKED_MESSAGE, full.toString());
        assertEquals(BLOCKED_MESSAGE,
                ChatPersistContent.body(blocked.get(), BLOCKED_MESSAGE, full.toString(), DISCLAIMER));
        assertTrue(events.stream().allMatch(event -> ChatConstants.EVENT_TOKEN.equals(event.event())));
    }

    @Test
    void keywordWithFullWidthSpaceAcrossTokensIsAlsoBlocked() {
        StringBuffer full = new StringBuffer();
        AtomicBoolean blocked = new AtomicBoolean(false);

        List<ServerSentEvent<String>> events = GuardedSse.guardedSse(
                        Flux.just("请用", "　", "药建", "议"),
                        full, blocked, KEYWORDS, BLOCKED_MESSAGE)
                .collectList()
                .block();

        String emitted = events.stream().map(ServerSentEvent::data).collect(Collectors.joining());
        assertTrue(blocked.get());
        // 与上例一致：标准文案含关键词，只断言原始模型内容未下发
        assertEquals(BLOCKED_MESSAGE, emitted);
        assertFalse(emitted.replace(" ", "").replace("\u3000", "").contains("请用药建议"));
        assertEquals(BLOCKED_MESSAGE, full.toString());
        assertEquals(BLOCKED_MESSAGE,
                ChatPersistContent.body(blocked.get(), BLOCKED_MESSAGE, full.toString(), DISCLAIMER));
    }

    @Test
    void safeTokensPassThroughAndPersistGeneratedBody() {
        StringBuffer full = new StringBuffer();
        AtomicBoolean blocked = new AtomicBoolean(false);

        List<ServerSentEvent<String>> events = GuardedSse.guardedSse(
                        Flux.just("建议", "多休息", "多喝水"),
                        full, blocked, KEYWORDS, BLOCKED_MESSAGE)
                .collectList()
                .block();

        String emitted = events.stream().map(ServerSentEvent::data).collect(Collectors.joining());
        assertFalse(blocked.get());
        assertEquals("建议多休息多喝水", emitted);
        assertEquals("建议多休息多喝水", full.toString());
        assertEquals("建议多休息多喝水\n\n" + DISCLAIMER,
                ChatPersistContent.body(blocked.get(), BLOCKED_MESSAGE, full.toString(), DISCLAIMER));
    }

    @Test
    void flushOnCancelPersistsHeldBackTail() {
        StringBuffer full = new StringBuffer();
        AtomicBoolean blocked = new AtomicBoolean(false);

        // 第一个 token 不足 holdback 无法下发，第二个 token 后仅放行 2 个字符即被 take(1) 取消；
        // 取消时守卫仍滞留尾部，必须由 doOnCancel 冲刷进 full，落库不丢已生成内容
        List<ServerSentEvent<String>> events = GuardedSse.guardedSse(
                        Flux.just("建议您先休息", "，多喝水"),
                        full, blocked, KEYWORDS, BLOCKED_MESSAGE)
                .take(1)
                .collectList()
                .block();

        assertEquals(List.of("建议"), events.stream().map(ServerSentEvent::data).collect(Collectors.toList()));
        assertFalse(blocked.get());
        // SSE 只下发了已放行前缀，但 full 在取消时补入了滞留尾部
        assertEquals("建议您先休息，多喝水", full.toString());
        assertEquals("建议您先休息，多喝水\n\n" + DISCLAIMER,
                ChatPersistContent.body(blocked.get(), BLOCKED_MESSAGE, full.toString(), DISCLAIMER));
    }

    @Test
    void normalCompletionDoesNotDuplicateFlushedTail() {
        StringBuffer full = new StringBuffer();
        AtomicBoolean blocked = new AtomicBoolean(false);

        // 完成时守卫已在 concatWith 中补发尾部，doOnComplete 的冲刷必须为空操作，避免重复落库
        GuardedSse.guardedSse(Flux.just("建议您先休息"), full, blocked, KEYWORDS, BLOCKED_MESSAGE)
                .collectList()
                .block();

        assertFalse(blocked.get());
        assertEquals("建议您先休息", full.toString());
    }

    @Test
    void refusalAwareOutputPassesModelRefusal() {
        StringBuffer full = new StringBuffer();
        AtomicBoolean blocked = new AtomicBoolean(false);

        List<ServerSentEvent<String>> events = GuardedSse.guardedSse(
                        Flux.just("抱歉，我不能", "提供用药", "建议，请咨询医生"),
                        full, blocked, KEYWORDS, BLOCKED_MESSAGE, null, true)
                .collectList()
                .block();

        String emitted = events.stream().map(ServerSentEvent::data).collect(Collectors.joining());
        assertFalse(blocked.get());
        assertEquals("抱歉，我不能提供用药建议，请咨询医生", emitted);
        assertEquals("抱歉，我不能提供用药建议，请咨询医生", full.toString());
    }
}
