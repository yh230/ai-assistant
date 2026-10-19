package com.aiassistant.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 落库内容决策纯函数单测：拦截正文、正常正文、引用抑制与保留
 *
 * @author yh
 * @date 2026-10-19
 */
class ChatPersistContentTest {

    private static final String DISCLAIMER = "以上内容仅供参考，不能替代专业医疗建议。";

    @Test
    void blockedBodyUsesBlockedMessageOnly() {
        String body = ChatPersistContent.body(true, "标准拦截文案", "不安全的原始部分正文", DISCLAIMER);

        assertEquals("标准拦截文案", body);
    }

    @Test
    void normalBodyAppendsDisclaimer() {
        String body = ChatPersistContent.body(false, "标准拦截文案", "正常回复正文", DISCLAIMER);

        assertEquals("正常回复正文\n\n" + DISCLAIMER, body);
    }

    @Test
    void referencesSuppressedWhenBlocked() {
        assertNull(ChatPersistContent.references(true, false, "[\"doc\"]"));
    }

    @Test
    void referencesSuppressedWhenFallback() {
        assertNull(ChatPersistContent.references(false, true, "[\"doc\"]"));
    }

    @Test
    void referencesSuppressedWhenEmpty() {
        assertNull(ChatPersistContent.references(false, false, ""));
        assertNull(ChatPersistContent.references(false, false, null));
    }

    @Test
    void referencesKeptWhenModelAnswered() {
        assertEquals("[\"doc\"]", ChatPersistContent.references(false, false, "[\"doc\"]"));
    }
}
