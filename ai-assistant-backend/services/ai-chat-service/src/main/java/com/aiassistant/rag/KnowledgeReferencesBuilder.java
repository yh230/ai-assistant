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
 * 提供两个静态类型的入口，不做任何运行时类型分派（无需 {@code instanceof}）：
 * <ul>
 *   <li>{@link #build(List, int)}：过渡方法，接收旧的 {@link Document} 入参，
 *       内部转换为 {@link KnowledgeStore.ScoredChunk}（metadata 存在 documentId 时取真实值，
 *       否则为 0；缺失 chunkIndex 时以列表下标兜底；score=0.0）后委托给
 *       {@link #buildFromChunks(List, int)}。</li>
 *   <li>{@link #buildFromChunks(List, int)}：新的 chunk 级方法，直接接收
 *       {@link KnowledgeStore.ScoredChunk}，保留真实 documentId/chunkIndex/score。</li>
 * </ul>
 * <p>
 * <b>Task 7 迁移要求</b>：消费者（当前为 {@code PatientChatServiceImpl}）必须改用
 * {@link #buildFromChunks(List, int)}，随后删除过渡方法 {@link #build(List, int)}。
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
     * 构建引用来源 JSON（过渡方法，仅接收旧版 {@link Document}）。
     * <p>
     * 内部把每个 Document 转换为 {@link KnowledgeStore.ScoredChunk} 后委托
     * {@link #buildFromChunks(List, int)}：metadata 存在 documentId 时取真实值（缺失为 0），
     * chunkIndex 缺失时使用列表下标，score 固定为 0.0，section/source 取自 metadata。
     * <p>
     * <b>Task 7 必须把调用方切换到 {@link #buildFromChunks(List, int)} 并删除本方法。</b>
     *
     * @param hits          旧版命中文档（按相关度降序）
     * @param maxReferences 引用条数上限
     * @return JSON 数组字符串；无有效来源返回空串
     */
    public String build(List<Document> hits, int maxReferences) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        List<KnowledgeStore.ScoredChunk> chunks = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeStore.ScoredChunk chunk = fromDocument(hits.get(i), i);
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return buildFromChunks(chunks, maxReferences);
    }

    /**
     * 构建引用来源 JSON（chunk 级、静态类型入口）：按 {@code documentId:chunkIndex} 去重、
     * 保留相似度排序、限量。
     *
     * @param hits          检索命中片段（按相关度降序）
     * @param maxReferences 引用条数上限
     * @return JSON 数组字符串；无有效来源返回空串
     */
    public String buildFromChunks(List<KnowledgeStore.ScoredChunk> hits, int maxReferences) {
        if (hits == null || hits.isEmpty() || maxReferences <= 0) {
            return "";
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> refs = new ArrayList<>();
        for (KnowledgeStore.ScoredChunk chunk : hits) {
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
     * 把旧版 {@link Document} 转换为命中片段；null 入参返回 null
     *
     * @param document      旧版命中文档
     * @param fallbackIndex 缺少 chunkIndex 时使用的列表下标
     * @return 命中片段
     */
    private KnowledgeStore.ScoredChunk fromDocument(Document document, int fallbackIndex) {
        if (document == null) {
            return null;
        }
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
