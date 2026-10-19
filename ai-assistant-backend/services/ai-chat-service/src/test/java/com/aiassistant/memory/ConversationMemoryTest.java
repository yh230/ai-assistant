package com.aiassistant.memory;

import com.aiassistant.config.AiProperties;
import com.aiassistant.entity.ChatMessage;
import com.aiassistant.entity.ChatSession;
import com.aiassistant.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 会话记忆与输入预算单测（token 估算、预算内全量、超预算摘要、摘要持久化）
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class ConversationMemoryTest {

    /** 摘要前缀（与实现保持一致） */
    private static final String SUMMARY_PREFIX = "早期对话摘要：";

    @Mock
    private ChatSessionRepository sessionRepository;

    private AiProperties aiProperties;

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        memory = new ConversationMemory(sessionRepository, aiProperties);
    }

    @Test
    void estimatesCjkAndAsciiTokens() {
        // 汉字按 1 token/字
        assertEquals(2, ConversationMemory.estimateTokens("你好"));
        // ASCII 按 4 字/token 向上取整
        assertEquals(3, ConversationMemory.estimateTokens("hello world"));
        // 混合文本：2 个汉字 + 5 个 ASCII（→2）
        assertEquals(4, ConversationMemory.estimateTokens("你好world"));
        // 空值/空串
        assertEquals(0, ConversationMemory.estimateTokens(null));
        assertEquals(0, ConversationMemory.estimateTokens(""));
    }

    @Test
    void returnsAllHistoryWhenUnderBudget() {
        aiProperties.getChat().setContextTokenBudget(1000);
        List<ChatMessage> history = List.of(
                message("user", "我想了解下明天的天气"),
                message("assistant", "请问您想查哪个城市？"));

        List<Message> messages = memory.build(session("s1"), history);

        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals("我想了解下明天的天气", messages.get(0).getText());
        assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertEquals("请问您想查哪个城市？", messages.get(1).getText());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void keepsRecentMessagesAndBuildsSummaryWhenOverBudget() {
        aiProperties.getChat().setContextTokenBudget(100);
        String earlyContent = "早".repeat(80);
        List<ChatMessage> history = List.of(
                message("user", earlyContent),
                message("user", earlyContent),
                message("user", earlyContent),
                message("user", earlyContent),
                message("user", "最近一条消息"));

        ChatSession session = session("s2");
        List<Message> messages = memory.build(session, history);

        // 最近消息（70 token 预算内至少保留最后一条）+ 一条早期摘要
        assertEquals(2, messages.size());
        Message first = messages.get(0);
        assertInstanceOf(SystemMessage.class, first);
        String summaryText = first.getText();
        assertTrue(summaryText.startsWith(SUMMARY_PREFIX));
        String summary = summaryText.substring(SUMMARY_PREFIX.length());
        String[] items = summary.split("；");
        assertEquals(4, items.length);
        for (String item : items) {
            assertEquals(60, item.length());
            assertEquals("早".repeat(60), item);
        }

        Message last = messages.get(1);
        assertInstanceOf(UserMessage.class, last);
        assertEquals("最近一条消息", last.getText());

        // 摘要写回会话
        assertNotNull(session.getSummary());
        assertNotNull(session.getSummaryUpdatedAt());
        assertEquals(summary, session.getSummary());
        verify(sessionRepository, times(1)).save(session);
    }

    @Test
    void persistsSummaryOnceAcrossRepeatedBuilds() {
        aiProperties.getChat().setContextTokenBudget(90);
        String earlyContent = "早".repeat(80);
        List<ChatMessage> history = List.of(
                message("user", earlyContent),
                message("user", "最近一条消息"));
        ChatSession session = session("s3");

        memory.build(session, history);
        memory.build(session, history);

        // 摘要内容未变化时不重复写库
        verify(sessionRepository, times(1)).save(session);
    }

    /**
     * 构造消息实体。
     */
    private ChatMessage message(String role, String content) {
        return ChatMessage.builder().role(role).content(content).build();
    }

    /**
     * 构造会话实体。
     */
    private ChatSession session(String sessionId) {
        return ChatSession.builder().sessionId(sessionId).title("测试会话").build();
    }
}
