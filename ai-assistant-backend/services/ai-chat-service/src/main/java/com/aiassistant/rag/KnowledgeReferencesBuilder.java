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
 * 知识库引用来源构建器：命中片段按 {@code documentId:chunkIndex} 去重、截取摘要，
 * 产出包含 name/section/snippet/score 的 references 事件 JSON。
 * <p>
 * 兼容过渡：入参既支持 {@link KnowledgeStore.ScoredChunk}（chunk 级引用），
 * 也支持旧的 {@link Document}（按 documentId=0、列表下标作为 chunkIndex 转换）。
 * 由于泛型擦除无法同时声明两个 {@code build(List, int)} 重载，此处使用统一泛型入口
 * 在运行时分派，调用方签名保持不变。
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
     * 构建引用来源 JSON（按 chunk 去重、保留相似度排序、限量）
     *
     * @param hits          检索命中片段（按相关度降序）；元素为 ScoredChunk 或 Document
     * @param maxReferences 引用条数上限
     * @return JSON 数组字符串；无有效来源返回空串
     */
    public <T> String build(List<T> hits, int maxReferences) {
        if (hits == null || hits.isEmpty() || maxReferences <= 0) {
            return "";
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> refs = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeStore.ScoredChunk chunk = toScoredChunk(hits.get(i), i);
            if (chunk == null || chunk.source() == null || chunk.source().isBlank()) {
                continue;
            }
            String key = chunk.documentId() + ":" + chunk.chunkIndex();
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("name", chunk.source());
            ref.put("section", chunk.section() == null ? "" : chunk.section());
            ref.put("snippet", snippetOf(chunk.content()));
            ref.put("score", chunk.score());
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
     * 将兼容入参转换为命中片段；不支持的类型返回 null
     *
     * @param hit           ScoredChunk 或旧版 Document
     * @param fallbackIndex 旧版 Document 缺少 chunkIndex 时使用的列表下标
     * @return 命中片段
     */
    private KnowledgeStore.ScoredChunk toScoredChunk(Object hit, int fallbackIndex) {
        if (hit instanceof KnowledgeStore.ScoredChunk chunk) {
            return chunk;
        }
        if (hit instanceof Document document) {
            Map<String, Object> metadata = document.getMetadata();
            Object chunkIndex = metadata.get("chunkIndex");
            return new KnowledgeStore.ScoredChunk(
                    stringValue(metadata.get("source")),
                    longValue(metadata.get("documentId")),
                    chunkIndex == null ? fallbackIndex : intValue(chunkIndex),
                    stringValue(metadata.get("section")),
                    document.getText(),
                    0.0);
        }
        return null;
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

    /**
     * metadata 字符串取值
     *
     * @param value 原始值
     * @return 字符串；空值返回空串
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * metadata 长整型取值
     *
     * @param value 原始值
     * @return 长整型；无法解析时返回 0
     */
    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * metadata 整型取值
     *
     * @param value 原始值
     * @return 整型；无法解析时返回 0
     */
    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
