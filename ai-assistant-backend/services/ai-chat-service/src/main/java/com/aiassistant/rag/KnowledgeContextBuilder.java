package com.aiassistant.rag;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * 在历史消息末尾用户消息之前插入知识上下文
     *
     * @param history 对话历史（最后一条为当前用户问题）
     * @param hits    检索命中片段
     * @return 注入后的消息列表；无命中或无历史时原样返回
     */
    public List<Message> inject(List<Message> history, List<KnowledgeStore.ScoredChunk> hits) {
        if (hits == null || hits.isEmpty() || history == null || history.isEmpty()) {
            return history;
        }
        Message last = history.get(history.size() - 1);
        List<Message> result = new ArrayList<>(history.subList(0, history.size() - 1));
        StringBuilder context = new StringBuilder(KNOWLEDGE_OPEN).append('\n');
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeStore.ScoredChunk hit = hits.get(i);
            context.append('[').append(i + 1).append("] 来源：")
                    .append(hit.source()).append(" / 章节：").append(hit.section()).append('\n')
                    .append(escape(hit.content())).append("\n\n");
        }
        context.append(KNOWLEDGE_CLOSE);
        result.add(new UserMessage(context.toString()));
        result.add(last);
        return result;
    }

    /**
     * 去除正文中的知识块边界标签，防止文档伪造上下文边界
     *
     * @param text 片段正文
     * @return 清理后的正文
     */
    private String escape(String text) {
        return text == null ? "" : text.replace(KNOWLEDGE_CLOSE, "").replace(KNOWLEDGE_OPEN, "");
    }
}
