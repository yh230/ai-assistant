package com.aiassistant.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库引用来源构建器：命中片段按来源文件名去重、截取摘要，产出 references 事件 JSON
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeReferencesBuilder {

    /** 片段摘要最大长度（字符，超长追加省略号） */
    private static final int SNIPPET_MAX_LENGTH = 60;

    private final ObjectMapper objectMapper;

    /**
     * 构建引用来源 JSON（按来源文件名去重、保留相似度排序、限量）
     *
     * @param hits 检索命中片段（按相似度降序）
     * @param maxReferences 引用条数上限
     * @return JSON 数组字符串；无有效来源返回空串
     */
    public String build(List<Document> hits, int maxReferences) {
        if (hits == null || hits.isEmpty() || maxReferences <= 0) {
            return "";
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, String>> refs = new ArrayList<>();
        for (Document hit : hits) {
            Object source = hit.getMetadata().get("source");
            String name = source == null ? "" : String.valueOf(source);
            if (name.isEmpty() || !seen.add(name)) {
                continue;
            }
            Map<String, String> ref = new LinkedHashMap<>();
            ref.put("name", name);
            ref.put("snippet", snippetOf(hit.getText()));
            refs.add(ref);
            if (refs.size() >= maxReferences) {
                break;
            }
        }
        if (refs.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (Exception e) {
            log.warn("引用来源序列化失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 截取片段摘要（空白折叠 + 超长截断加省略号）
     *
     * @param text 片段全文
     * @return 摘要文本
     */
    private String snippetOf(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_MAX_LENGTH) + "…";
    }
}
