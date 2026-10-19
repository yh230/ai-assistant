package com.aiassistant.memory;

import com.aiassistant.config.AiProperties;
import com.aiassistant.entity.ChatMessage;
import com.aiassistant.entity.ChatSession;
import com.aiassistant.repository.ChatSessionRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话记忆与输入预算构建器
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>token 预算</b>：以 {@code ai.chat.context-token-budget} 为上限估算历史消息 token，
 *         未超预算时全部历史进入上下文。</li>
 *     <li><b>超预算压缩</b>：保留最近消息（占预算 70%），更早的消息用启发式摘要替代；
 *         摘要不调用模型，仅取早期（最近窗口之前）用户消息中<b>最后</b> 8 条片段拼接，并持久化到会话。</li>
 *     <li><b>摘要重生成</b>：每次超预算构建都基于完整早期区重新生成摘要（不使用 {@code createdAt}/{@code summaryUpdatedAt}
 *         判断新旧），因此从最近窗口中被挤出的消息一定被纳入；生成文本与会话已有摘要相同则不写库，
 *         否则写回 {@code summary} 并刷新 {@code summaryUpdatedAt}。</li>
 *     <li><b>预算计入摘要</b>：最近消息预算先扣除摘要（前缀 + 正文 + 消息开销）的估算 token，避免摘要占用导致溢出。</li>
 *     <li><b>启发式估算</b>：中日韩字符按 1 token/字，ASCII 按 4 字/token，其余按 2 字/token。</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
public class ConversationMemory {

    /** 最近消息可用预算占比（百分比） */
    private static final int RECENT_BUDGET_PERCENT = 70;

    /** 摘要纳入的早期用户消息条数上限 */
    private static final int MAX_SUMMARY_ITEMS = 8;

    /** 单条摘要内容截断字符数 */
    private static final int SUMMARY_ITEM_CHARS = 60;

    /** 摘要片段连接符 */
    private static final String SUMMARY_JOINER = "；";

    /** 摘要消息前缀 */
    private static final String SUMMARY_PREFIX = "早期对话摘要：";

    /** 单条消息的角色/分隔开销（token） */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private final ChatSessionRepository sessionRepository;

    private final AiProperties aiProperties;

    /**
     * 构造会话记忆构建器
     *
     * @param sessionRepository 会话仓储（摘要持久化）
     * @param aiProperties      AI 配置（token 预算）
     */
    public ConversationMemory(ChatSessionRepository sessionRepository, AiProperties aiProperties) {
        this.sessionRepository = sessionRepository;
        this.aiProperties = aiProperties;
    }

    /**
     * 按 token 预算构建上下文消息列表
     * <p>
     * 未超预算时返回全部历史消息；超预算时保留最近消息，并把更早（最近窗口之前）的消息压缩为一条系统摘要消息。
     * 每次都会基于完整早期区<b>最后</b> {@value #MAX_SUMMARY_ITEMS} 条用户消息重新生成摘要，
     * 不依赖 {@code summaryUpdatedAt} 判断新旧（被挤出最近窗口的消息创建时间可能早于摘要时间戳）。
     * 生成文本与会话已有摘要相同则不写库；不同则写回 {@code summary} 并刷新 {@code summaryUpdatedAt}。
     *
     * @param session 当前会话（可为空，为空时不复用/持久化摘要）
     * @param history 历史消息（按时间正序，含本轮用户消息）
     * @return Spring AI 上下文消息列表
     */
    public List<Message> build(ChatSession session, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int budget = aiProperties.getChat().getContextTokenBudget();
        if (budget <= 0 || totalTokens(history) <= budget) {
            return toMessages(history);
        }

        int recentBudget = budget * RECENT_BUDGET_PERCENT / 100;
        String existing = existingSummary(session);
        int recentStart = recentStartIndex(history, Math.max(0, recentBudget - summaryTokens(existing)));
        String summary = resolveSummary(session, history.subList(0, recentStart));
        int summaryTokenCount = summaryTokens(summary);
        if (summaryTokenCount > summaryTokens(existing)) {
            // 摘要实际占用超出预留额度时，按实际值收缩一次最近窗口（只调整一次，避免反复抖动）
            int adjustedStart = recentStartIndex(history, Math.max(0, recentBudget - summaryTokenCount));
            if (adjustedStart > recentStart) {
                recentStart = adjustedStart;
                summary = resolveSummary(session, history.subList(0, recentStart));
            }
        }
        summary = persistSummary(session, summary);

        List<Message> messages = new ArrayList<>();
        if (StringUtils.hasText(summary)) {
            messages.add(new SystemMessage(SUMMARY_PREFIX + summary));
        }
        messages.addAll(toMessages(history.subList(recentStart, history.size())));
        return messages;
    }

    /**
     * 估算文本 token 数：中日韩字符 1 token/字，ASCII 4 字/token，其余 2 字/token。
     *
     * @param text 文本（可为空）
     * @return 估算 token 数；空文本返回 0
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk++;
            } else if (codePoint < 0x80) {
                ascii++;
            } else {
                other++;
            }
        }
        return cjk + (ascii + 3) / 4 + (other + 1) / 2;
    }

    /**
     * 判断码点是否属于中日韩文字（汉字/日文假名/韩文，按 1 token/字计）。
     *
     * @param codePoint Unicode 码点
     * @return 是否中日韩文字
     */
    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * 统计消息列表的估算 token 总量（含每条消息固定开销）。
     *
     * @param messages 消息列表
     * @return 估算 token 总量
     */
    private int totalTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += messageTokens(message);
        }
        return total;
    }

    /**
     * 估算单条消息 token 数（正文估算 + 角色开销）。
     *
     * @param message 消息实体
     * @return 估算 token 数
     */
    private int messageTokens(ChatMessage message) {
        return MESSAGE_OVERHEAD_TOKENS + (message == null ? 0 : estimateTokens(message.getContent()));
    }

    /**
     * 从末尾向前选取最近消息，使其估算 token 不超过最近消息预算（至少保留最后一条）。
     *
     * @param history     历史消息
     * @param recentBudget 最近消息预算（token）
     * @return 最近消息的起始下标
     */
    private int recentStartIndex(List<ChatMessage> history, int recentBudget) {
        int start = history.size();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            int tokens = messageTokens(history.get(i));
            if (start < history.size() && used + tokens > recentBudget) {
                break;
            }
            used += tokens;
            start = i;
        }
        return start;
    }

    /**
     * 解析应当注入的摘要（不写库）：始终基于完整早期区重新生成；早期区没有可摘要的用户消息时保留已有摘要。
     *
     * @param session 当前会话（可为空）
     * @param older   早期消息（最近窗口之前）
     * @return 摘要文本；无任何摘要时返回空串
     */
    private String resolveSummary(ChatSession session, List<ChatMessage> older) {
        String generated = summarize(older);
        if (StringUtils.hasText(generated)) {
            return generated;
        }
        String existing = existingSummary(session);
        return StringUtils.hasText(existing) ? existing : generated;
    }

    /**
     * 取会话已有摘要（会话为空时返回 null）。
     *
     * @param session 当前会话
     * @return 已有摘要
     */
    private String existingSummary(ChatSession session) {
        return session == null ? null : session.getSummary();
    }

    /**
     * 计算摘要注入的估算 token（前缀 + 正文 + 消息固定开销）。
     *
     * @param summary 摘要文本
     * @return 估算 token；空摘要返回 0
     */
    private int summaryTokens(String summary) {
        if (!StringUtils.hasText(summary)) {
            return 0;
        }
        return MESSAGE_OVERHEAD_TOKENS + estimateTokens(SUMMARY_PREFIX + summary);
    }

    /**
     * 启发式摘要：取早期消息中<b>最后</b> {@value #MAX_SUMMARY_ITEMS} 条用户消息（按时间正序输出），
     * 每条压平空白并截断到 {@value #SUMMARY_ITEM_CHARS} 字，以「；」连接。
     *
     * @param older 早期消息
     * @return 摘要文本；无可摘要内容时返回空串
     */
    private String summarize(List<ChatMessage> older) {
        List<String> parts = new ArrayList<>();
        for (int i = older.size() - 1; i >= 0 && parts.size() < MAX_SUMMARY_ITEMS; i--) {
            ChatMessage message = older.get(i);
            if (message == null || !"user".equals(message.getRole()) || !StringUtils.hasText(message.getContent())) {
                continue;
            }
            String content = message.getContent().trim().replaceAll("\\s+", " ");
            if (content.length() > SUMMARY_ITEM_CHARS) {
                content = content.substring(0, SUMMARY_ITEM_CHARS);
            }
            parts.add(content);
        }
        Collections.reverse(parts);
        return String.join(SUMMARY_JOINER, parts);
    }

    /**
     * 将摘要持久化到会话：生成文本与会话已有摘要相同则不写库；不同则写回并刷新摘要时间戳。
     *
     * @param session  当前会话（可为空）
     * @param resolved 已解析的摘要文本
     * @return 实际注入的摘要文本
     */
    private String persistSummary(ChatSession session, String resolved) {
        if (session == null || !StringUtils.hasText(resolved) || resolved.equals(session.getSummary())) {
            return resolved;
        }
        session.setSummary(resolved);
        session.setSummaryUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
        return resolved;
    }

    /**
     * 将实体消息转换为 Spring AI 消息（user 为用户消息，其余为助手消息）。
     *
     * @param messages 实体消息列表
     * @return Spring AI 消息列表
     */
    private List<Message> toMessages(List<ChatMessage> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if ("user".equals(message.getRole())) {
                result.add(new UserMessage(message.getContent()));
            } else {
                result.add(new AssistantMessage(message.getContent()));
            }
        }
        return result;
    }
}
