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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 会话记忆与输入预算单测（token 估算、预算内全量、超预算摘要、摘要重生成与写库条件）
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
    void summarizesLatestEarlyUserTurnsOnly() {
        aiProperties.getChat().setContextTokenBudget(100);
        List<ChatMessage> history = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusMinutes(30);
        for (int i = 1; i <= 10; i++) {
            history.add(message("user", "编号" + i + "：" + "内".repeat(70), base.plusMinutes(i)));
        }
        history.add(message("user", "最近一条消息", base.plusMinutes(20)));

        List<Message> messages = memory.build(session("s5"), history);

        assertEquals(2, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        String summary = messages.get(0).getText().substring(SUMMARY_PREFIX.length());
        String[] items = summary.split("；");
        // 只纳入早期区（最近窗口之前）最后 8 条用户消息，最早两条被丢弃，输出保持时间正序
        assertEquals(8, items.length);
        assertTrue(items[0].startsWith("编号3："));
        assertTrue(items[7].startsWith("编号10："));
        assertFalse(summary.contains("编号1："));
        assertFalse(summary.contains("编号2："));
    }

    @Test
    void keepsExistingSummaryWhenOlderHasNoUserMessages() {
        aiProperties.getChat().setContextTokenBudget(60);
        LocalDateTime summarizedAt = LocalDateTime.now().minusHours(1);
        List<ChatMessage> history = List.of(
                message("assistant", "助".repeat(80), summarizedAt.minusMinutes(10)),
                message("user", "最近一条消息", LocalDateTime.now()));

        ChatSession session = session("s6");
        session.setSummary("既有摘要");
        session.setSummaryUpdatedAt(summarizedAt);

        List<Message> messages = memory.build(session, history);

        assertEquals(2, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        // 早期区没有用户消息，保留既有摘要（既不重新生成也不写库）
        assertEquals(SUMMARY_PREFIX + "既有摘要", messages.get(0).getText());
        assertInstanceOf(UserMessage.class, messages.get(1));
        assertEquals("既有摘要", session.getSummary());
        assertEquals(summarizedAt, session.getSummaryUpdatedAt());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void regeneratesSummaryForDisplacedOlderMessages() {
        aiProperties.getChat().setContextTokenBudget(100);
        List<ChatMessage> history = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now().minusMinutes(30);
        for (int i = 1; i <= 10; i++) {
            history.add(message("user", "编号" + i + "：" + "内".repeat(70), base.plusMinutes(i)));
        }
        history.add(message("user", "最近一条消息", base.plusMinutes(20)));

        ChatSession session = session("s7");
        session.setSummary("旧摘要");
        // 摘要时间戳晚于所有消息创建时间：旧实现会因此复用「旧摘要」，被挤出的早期消息永远不会进入摘要
        LocalDateTime summarizedAt = LocalDateTime.now().minusSeconds(1);
        session.setSummaryUpdatedAt(summarizedAt);

        List<Message> messages = memory.build(session, history);

        assertEquals(2, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        String summary = messages.get(0).getText().substring(SUMMARY_PREFIX.length());
        String[] items = summary.split("；");
        // 每次超预算构建都基于完整早期区重新生成：早期区最后 8 条用户消息全部被纳入
        assertEquals(8, items.length);
        assertTrue(items[0].startsWith("编号3："));
        assertTrue(items[7].startsWith("编号10："));
        assertFalse(summary.contains("编号1："));
        assertFalse(summary.contains("编号2："));
        // 生成文本与旧摘要不同 → 写回并推进时间戳
        assertNotEquals("旧摘要", session.getSummary());
        assertEquals(summary, session.getSummary());
        assertTrue(session.getSummaryUpdatedAt().isAfter(summarizedAt));
        verify(sessionRepository, times(1)).save(session);
    }

    @Test
    void doesNotSaveWhenRegeneratedSummaryMatchesExisting() {
        aiProperties.getChat().setContextTokenBudget(90);
        LocalDateTime summarizedAt = LocalDateTime.now();
        List<ChatMessage> history = List.of(
                message("user", "早".repeat(80), summarizedAt.minusMinutes(10)),
                message("user", "最近一条消息", summarizedAt.minusMinutes(5)));

        ChatSession session = session("s8");
        // 与「早」×60 的启发式摘要完全一致
        session.setSummary("早".repeat(60));
        session.setSummaryUpdatedAt(summarizedAt);

        List<Message> messages = memory.build(session, history);

        assertEquals(2, messages.size());
        assertEquals(SUMMARY_PREFIX + "早".repeat(60), messages.get(0).getText());
        // 重新生成的文本未变化 → 不写库、不推进时间戳
        assertEquals("早".repeat(60), session.getSummary());
        assertEquals(summarizedAt, session.getSummaryUpdatedAt());
        verify(sessionRepository, never()).save(any());
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

    @Test
    void reservedTokensForceSummaryWhenHistoryAloneUnderBudget() {
        aiProperties.getChat().setContextTokenBudget(100);
        List<ChatMessage> history = List.of(
                message("user", "早".repeat(80)),
                message("user", "最近一条消息"));

        ConversationMemory.Context context = memory.buildContext(session("s9"), history, 60);

        assertTrue(context.summary().startsWith("早".repeat(60)));
        assertEquals(1, context.messages().size());
        assertEquals("最近一条消息", context.messages().get(0).getText());
    }

    /**
     * 构造消息实体。
     */
    private ChatMessage message(String role, String content) {
        return message(role, content, null);
    }

    /**
     * 构造带创建时间的消息实体（用于构造「被挤出最近窗口」的历史对话）。
     */
    private ChatMessage message(String role, String content, LocalDateTime createdAt) {
        return ChatMessage.builder().role(role).content(content).createdAt(createdAt).build();
    }

    /**
     * 构造会话实体。
     */
    private ChatSession session(String sessionId) {
        return ChatSession.builder().sessionId(sessionId).title("测试会话").build();
    }
}
