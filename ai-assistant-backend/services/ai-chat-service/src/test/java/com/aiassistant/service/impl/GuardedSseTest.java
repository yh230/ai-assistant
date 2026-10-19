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
        // 关键词被拆到多个 token，守卫必须在映射为 SSE 前命中，任何已下发前缀都不得拼出完整关键词
        assertTrue(blocked.get());
        assertFalse(emitted.contains("用药建议"));
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
        assertFalse(emitted.replace(" ", "").replace("\u3000", "").contains("用药建议"));
        assertEquals(BLOCKED_MESSAGE, full.toString());
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
}
