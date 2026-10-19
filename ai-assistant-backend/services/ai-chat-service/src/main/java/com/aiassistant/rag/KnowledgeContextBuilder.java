package com.aiassistant.rag;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把检索片段包装为独立 user 消息，放在最后一条用户消息之前，避免进入 system 指令层
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
public class KnowledgeContextBuilder {

    /** 知识块边界标签 */
    private static final String KNOWLEDGE_OPEN = "<knowledge>";

    /** 知识块边界闭合标签 */
    private static final String KNOWLEDGE_CLOSE = "</knowledge>";

    /** 知识块边界标签（开/闭，大小写不敏感），用于剥离不可信字段中的伪造边界 */
    private static final Pattern KNOWLEDGE_TAG =
            Pattern.compile("</?knowledge>", Pattern.CASE_INSENSITIVE);

    /**
     * 在历史消息中最后一条用户消息之前插入知识上下文；无用户消息时追加到末尾
     *
     * @param history 对话历史
     * @param hits    检索命中片段
     * @return 注入后的消息列表；无命中或无历史时原样返回
     */
    public List<Message> inject(List<Message> history, List<KnowledgeStore.ScoredChunk> hits) {
        if (hits == null || hits.isEmpty() || history == null || history.isEmpty()) {
            return history;
        }
        List<Message> result = new ArrayList<>(history);
        int insertAt = history.size();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage) {
                insertAt = i;
                break;
            }
        }
        StringBuilder context = new StringBuilder(KNOWLEDGE_OPEN).append('\n');
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeStore.ScoredChunk hit = hits.get(i);
            context.append('[').append(i + 1).append("] 来源：")
                    .append(escape(hit.source())).append(" / 章节：").append(escape(hit.section())).append('\n')
                    .append(escape(hit.content())).append("\n\n");
        }
        context.append(KNOWLEDGE_CLOSE);
        result.add(insertAt, new UserMessage(context.toString()));
        return result;
    }

    /**
     * 去除字段中的知识块边界标签，防止文档伪造上下文边界
     * <p>
     * 大小写不敏感，并重复剥离直至文本稳定，避免标签被移除后拼接出新的边界标签
     * （如 {@code <k</knowledge>nowledge>}、{@code <<knowledge>/knowledge>}）。
     *
     * @param text 来源、章节或正文
     * @return 清理后的文本
     */
    private String escape(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text;
        String previous;
        do {
            previous = cleaned;
            cleaned = KNOWLEDGE_TAG.matcher(cleaned).replaceAll("");
        } while (!cleaned.equals(previous));
        return cleaned;
    }
}
