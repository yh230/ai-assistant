package com.aiassistant.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 知识库向量存储抽象：simple 与 Elasticsearch 两种实现
 * <p>
 * 仅定义接口，具体实现由后续任务提供。检索结果统一为 {@link ScoredChunk}，
 * 供上下文注入、引用展示与 groundedness 校验复用。
 *
 * @author yh
 * @date 2026-10-19
 */
public interface KnowledgeStore {

    /**
     * 幂等写入文档切片（同文档旧切片由实现方按 previousChunkCount 清理）
     *
     * @param hospitalId         医院 ID
     * @param documentId         文档 ID
     * @param documentName       文档名（写入来源字段）
     * @param previousChunkCount 该文档此前已写入的切片数
     * @param chunks             新切片列表
     */
    void upsertDocument(Long hospitalId, Long documentId, String documentName,
                        int previousChunkCount, List<Document> chunks);

    /**
     * 删除文档的全部切片
     *
     * @param hospitalId 医院 ID
     * @param documentId 文档 ID
     * @param chunkCount 该文档已写入的切片数
     */
    void deleteDocument(Long hospitalId, Long documentId, int chunkCount);

    /**
     * 检索本院知识库片段
     *
     * @param hospitalId 医院 ID
     * @param query      检索问题
     * @param topK       片段数上限
     * @param threshold  相似度阈值
     * @return 命中片段（按相关度降序）
     */
    List<ScoredChunk> search(Long hospitalId, String query, int topK, double threshold);

    /**
     * 检索命中片段
     *
     * @param source     来源文档名
     * @param documentId 文档 ID
     * @param chunkIndex 切片序号
     * @param section    章节标题（可为空）
     * @param content    切片正文
     * @param score      相关度得分
     */
    record ScoredChunk(String source, Long documentId, int chunkIndex,
                       String section, String content, double score) {
    }
}
