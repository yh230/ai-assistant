package com.aiassistant.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 倒数排名融合（Reciprocal Rank Fusion）：合并 BM25 与 kNN 两路检索结果
 * <p>
 * 每个命中片段的融合得分为 {@code sum(1 / (rrfK + rank))}，rank 从 1 开始；同一片段在
 * 两路结果中重复出现时得分累加、只保留一条（以先出现的 BM25 结果为准）。融合只依赖排名、
 * 不依赖两路原始分值的量纲，避免余弦相似度与 BM25 分数不可比的问题。
 * <p>
 * 排序按融合得分降序；得分相同时保持首次出现顺序（BM25 先于 kNN），保证结果稳定可复现。
 * 切片以 {@code documentId:chunkIndex} 为唯一键去重。
 *
 * @author yh
 * @date 2026-10-19
 */
public final class RrfFuser {

    /**
     * 工具类禁止实例化
     */
    private RrfFuser() {
    }

    /**
     * 融合 BM25 与 kNN 检索结果
     *
     * @param bm25 关键词检索结果（按相关度降序，可为空）
     * @param knn  向量检索结果（按相关度降序，可为空）
     * @param topK 返回片段数上限（小于等于 0 时返回空列表）
     * @param rrfK RRF 平滑常数（常用 60，负数按 0 处理）
     * @return 融合后的片段（按融合得分降序，最多 topK 条）
     */
    public static List<KnowledgeStore.ScoredChunk> fuse(
            List<KnowledgeStore.ScoredChunk> bm25,
            List<KnowledgeStore.ScoredChunk> knn,
            int topK, int rrfK) {
        if (topK <= 0) {
            return List.of();
        }
        int smooth = Math.max(rrfK, 0);
        Map<String, Double> scores = new HashMap<>();
        Map<String, KnowledgeStore.ScoredChunk> byKey = new LinkedHashMap<>();
        addRanks(bm25, smooth, scores, byKey);
        addRanks(knn, smooth, scores, byKey);

        record Ranked(KnowledgeStore.ScoredChunk chunk, double score) {
        }
        List<Ranked> ranked = new ArrayList<>(scores.size());
        // 按首次出现顺序遍历，配合稳定排序实现“同分时 BM25 优先、其次原始排名”
        byKey.forEach((key, chunk) -> ranked.add(new Ranked(chunk, scores.get(key))));
        return ranked.stream()
                .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                .limit(topK)
                .map(r -> new KnowledgeStore.ScoredChunk(r.chunk().source(), r.chunk().documentId(),
                        r.chunk().chunkIndex(), r.chunk().section(), r.chunk().content(), r.score()))
                .toList();
    }

    /**
     * 累加单路检索结果的倒数排名得分（同一路内的重复片段只计一次）
     *
     * @param list   单路检索结果
     * @param rrfK   平滑常数（已归一为非负）
     * @param scores 融合得分表（key=documentId:chunkIndex）
     * @param byKey  片段表（保留首次出现的片段内容）
     */
    private static void addRanks(List<KnowledgeStore.ScoredChunk> list, int rrfK,
                                 Map<String, Double> scores,
                                 Map<String, KnowledgeStore.ScoredChunk> byKey) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            KnowledgeStore.ScoredChunk chunk = list.get(i);
            if (chunk == null) {
                continue;
            }
            String key = keyOf(chunk);
            if (!seen.add(key)) {
                continue;
            }
            scores.merge(key, 1.0 / (rrfK + i + 1), Double::sum);
            byKey.putIfAbsent(key, chunk);
        }
    }

    /**
     * 片段唯一键
     *
     * @param chunk 片段
     * @return documentId:chunkIndex
     */
    private static String keyOf(KnowledgeStore.ScoredChunk chunk) {
        return chunk.documentId() + ":" + chunk.chunkIndex();
    }
}
