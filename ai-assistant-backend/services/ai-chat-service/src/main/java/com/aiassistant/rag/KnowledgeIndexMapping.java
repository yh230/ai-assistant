package com.aiassistant.rag;

/**
 * 知识片段索引 mapping 生成器（纯函数，便于单测）
 * <p>
 * 索引同时服务 BM25 关键词检索（{@code content}、{@code section} 使用 2-3 元 ngram 分词，
 * 兼容中文无空格场景）与 kNN 向量检索（{@code embedding} 为 dense_vector、余弦相似度），
 * 其余字段为医院隔离与引用展示所需的标量字段。
 * <p>
 * 返回的 JSON 直接交给 Elasticsearch Java 客户端建索引（{@code CreateIndexRequest#withJson}），
 * 因此保持为合法 JSON；分词器名称由配置下发，非标识符字符会被替换为下划线，避免生成非法 JSON。
 *
 * @author yh
 * @date 2026-10-19
 */
public final class KnowledgeIndexMapping {

    /** 默认分词器名称（配置缺失时兜底） */
    private static final String DEFAULT_ANALYZER = "cjk_ngram";

    /** ngram 最小长度 */
    private static final int MIN_GRAM = 2;

    /** ngram 最大长度 */
    private static final int MAX_GRAM = 3;

    /**
     * 工具类禁止实例化
     */
    private KnowledgeIndexMapping() {
    }

    /**
     * 生成知识片段索引的 settings + mappings JSON
     *
     * @param dimensions 向量维度（必须大于 0，与嵌入模型输出一致）
     * @param analyzer   分词器名称（为空时使用 cjk_ngram）
     * @return 合法的索引定义 JSON
     * @throws IllegalArgumentException 向量维度非法
     */
    public static String json(int dimensions, String analyzer) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("向量维度必须大于 0: " + dimensions);
        }
        String name = normalizeAnalyzer(analyzer);
        String tokenizer = name + "_tokenizer";
        return "{\"settings\":{\"analysis\":{"
                + "\"analyzer\":{\"" + name + "\":{\"type\":\"custom\",\"tokenizer\":\"" + tokenizer + "\"}},"
                + "\"tokenizer\":{\"" + tokenizer + "\":{\"type\":\"ngram\",\"min_gram\":" + MIN_GRAM
                + ",\"max_gram\":" + MAX_GRAM + ",\"token_chars\":[\"letter\",\"digit\"]}}"
                + "}},"
                + "\"mappings\":{\"properties\":{"
                + "\"hospitalId\":{\"type\":\"long\"},"
                + "\"documentId\":{\"type\":\"long\"},"
                + "\"documentName\":{\"type\":\"keyword\"},"
                + "\"chunkIndex\":{\"type\":\"integer\"},"
                + "\"section\":{\"type\":\"text\",\"analyzer\":\"" + name + "\"},"
                + "\"content\":{\"type\":\"text\",\"analyzer\":\"" + name + "\"},"
                + "\"embedding\":{\"type\":\"dense_vector\",\"dims\":" + dimensions
                + ",\"index\":true,\"similarity\":\"cosine\"}"
                + "}}}";
    }

    /**
     * 归一化分词器名称：仅保留字母、数字与下划线，空值回退默认名称
     *
     * @param analyzer 原始名称
     * @return 合法的分词器名称
     */
    private static String normalizeAnalyzer(String analyzer) {
        if (analyzer == null || analyzer.isBlank()) {
            return DEFAULT_ANALYZER;
        }
        String name = analyzer.trim().replaceAll("[^A-Za-z0-9_]", "_");
        return name.isBlank() ? DEFAULT_ANALYZER : name;
    }
}
