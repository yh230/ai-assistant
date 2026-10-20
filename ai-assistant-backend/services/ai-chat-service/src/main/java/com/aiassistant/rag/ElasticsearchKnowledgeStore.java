package com.aiassistant.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.json.JsonData;
import com.aiassistant.config.AiProperties;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库向量存储 Elasticsearch 实现：BM25 关键词检索 + kNN 向量检索，RRF 融合
 * <p>
 * 索引同时保存切片正文与 dense_vector 向量；检索时两路各取 {@code topK*2} 条候选，
 * 再由 {@link RrfFuser} 融合为 {@code topK} 条，避免在两路不可比的分值上做加权。
 * 所有查询均带 {@code hospitalId} 过滤，医院级隔离由索引字段保证。
 * <p>
 * 写入使用确定性 ID {@code documentId:chunkIndex}（幂等覆盖），更新流程为“先完成全部嵌入、
 * 再批量 upsert 新切片、成功后才按 {@code chunkIndex >= 新切片数} 清理越界旧切片”。
 * 嵌入失败、批量写入 IO 异常或存在失败项时直接抛错，旧切片保持完整，不会出现空窗期或
 * 半成品文档。删除按 {@code hospitalId + documentId} 走 delete-by-query。
 * <p>
 * 嵌入或 ES 调用失败时记录异常并降级为空结果，保证对话主链路不因检索故障中断；
 * 维度与索引不一致属于部署错误，在初始化时明确失败。
 *
 * @author yh
 * @date 2026-10-19
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.rag.store", havingValue = "elasticsearch")
public class ElasticsearchKnowledgeStore implements KnowledgeStore {

    /** RRF 平滑常数（业界常用 60） */
    private static final int RRF_K = 60;

    /** 嵌入失败计数指标（Micrometer 全局注册表，随应用注册表导出） */
    private static final String EMBEDDING_FAILURE_METRIC = "ai.rag.embedding.failures";

    /** Elasticsearch Java 客户端 */
    private final ElasticsearchClient client;

    /** 嵌入模型 */
    private final EmbeddingModel embeddingModel;

    /** ES 索引配置（索引名与分词器） */
    private final AiProperties.Elasticsearch config;

    /** 向量维度（与索引 mapping 的 dense_vector dims 一致） */
    private final int dimensions;

    /**
     * 构造器注入
     *
     * @param client         Elasticsearch Java 客户端
     * @param embeddingModel 嵌入模型
     * @param properties     AI 配置（索引名、分词器、向量维度）
     */
    public ElasticsearchKnowledgeStore(ElasticsearchClient client, EmbeddingModel embeddingModel,
                                       AiProperties properties) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.config = properties.getRag().getElasticsearch();
        this.dimensions = properties.getRag().getEmbeddingDimensions();
    }

    /**
     * 启动时确保索引存在：缺失则按 {@link KnowledgeIndexMapping} 创建，已存在则校验向量维度
     * <p>
     * 维度不一致属于部署错误，直接抛出异常阻断启动；ES 暂不可达（可能晚于应用启动）时仅记录
     * 错误、不阻断启动，后续读写会重试。
     */
    @PostConstruct
    void ensureIndex() {
        String index = config.getIndexName();
        try {
            boolean exists = client.indices().exists(e -> e.index(index)).value();
            if (!exists) {
                client.indices().create(c -> c.index(index)
                        .withJson(new StringReader(
                                KnowledgeIndexMapping.json(dimensions, config.getAnalyzer()))));
                log.info("知识索引已创建: index={}, dims={}, analyzer={}",
                        index, dimensions, config.getAnalyzer());
                return;
            }
            int actual = existingDimensions();
            if (actual > 0 && actual != dimensions) {
                throw new IllegalStateException("知识索引向量维度不匹配: index=" + index
                        + ", actual=" + actual + ", configured=" + dimensions
                        + "; 请删除索引重建，或修正 ai.rag.embedding-dimensions");
            }
            if (actual <= 0) {
                log.warn("知识索引缺少 embedding 向量字段，跳过维度校验: index={}", index);
            }
        } catch (IllegalStateException e) {
            log.error("知识索引初始化失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("知识索引初始化失败（继续启动，读写时将重试）: index={}", index, e);
        }
    }

    /**
     * 幂等写入文档切片：先嵌入、再按确定性 ID 批量 upsert、成功后才清理越界旧切片
     * <p>
     * 执行顺序保证“失败不破坏旧数据”：
     * <ol>
     *     <li>完成全部切片的嵌入（任一切片嵌入失败直接抛错，索引内容不变）；</li>
     *     <li>以 {@code documentId:chunkIndex} 为 ID 批量 upsert，同序号旧切片被覆盖；</li>
     *     <li>bulk 返回 {@code errors()==true} 时抛出 {@link IllegalStateException}（含失败项明细），
     *         不执行旧切片清理；</li>
     *     <li>bulk 成功后按 {@code hospitalId + documentId + chunkIndex >= chunks.size()} 删除
     *         新切片数之外的旧切片（文档变短时生效）；</li>
     *     <li>写、删均完成后统一刷新一次索引。</li>
     * </ol>
     *
     * @param hospitalId         医院 ID（null 时按 no-op 处理）
     * @param documentId         文档 ID（null 时按 no-op 处理）
     * @param documentName       文档名
     * @param previousChunkCount 该文档此前已写入的切片数（仅保留接口语义，清理边界以本次切片数为准）
     * @param chunks             新切片列表
     */
    @Override
    public void upsertDocument(Long hospitalId, Long documentId, String documentName,
                               int previousChunkCount, List<Document> chunks) {
        if (hospitalId == null || documentId == null) {
            log.warn("upsertDocument 忽略空 hospitalId/documentId: hospitalId={}, documentId={}",
                    hospitalId, documentId);
            return;
        }
        List<Document> safeChunks = chunks == null ? List.of() : chunks;
        // 1. 先完成全部嵌入：任一切片失败直接抛出，索引中的旧切片保持不变
        List<BulkOperation> operations = new ArrayList<>(safeChunks.size());
        for (int i = 0; i < safeChunks.size(); i++) {
            Document chunk = safeChunks.get(i);
            if (chunk == null) {
                continue;
            }
            int chunkIndex = intValue(chunk.getMetadata().get("chunkIndex"), i);
            String section = stringValue(chunk.getMetadata().get("section"));
            String text = chunk.getText() == null ? "" : chunk.getText();
            float[] vector = embedForWrite(text, documentId, chunkIndex);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("hospitalId", hospitalId);
            source.put("documentId", documentId);
            source.put("documentName", documentName == null ? "" : documentName);
            source.put("chunkIndex", chunkIndex);
            source.put("section", section);
            source.put("content", text);
            source.put("embedding", toFloatList(vector));
            operations.add(BulkOperation.of(op -> op.index(idx -> idx
                    .index(config.getIndexName())
                    .id(vectorId(documentId, chunkIndex))
                    .document(source))));
        }

        // 2. 批量 upsert 新切片；失败时抛错且不触碰旧切片
        if (!operations.isEmpty()) {
            bulkUpsert(hospitalId, documentId, operations);
        }

        // 3. 写入成功后才清理越界旧切片（文档变短时生效）
        deleteStaleChunks(hospitalId, documentId, safeChunks.size());

        // 4. 写、删完成后统一刷新一次
        refresh();
    }

    /**
     * 批量 upsert 切片：存在失败项时抛错，交由上层重试，旧切片保持完整
     *
     * @param hospitalId 医院 ID（仅用于日志/异常信息）
     * @param documentId 文档 ID（仅用于日志/异常信息）
     * @param operations bulk 操作列表
     * @throws IllegalStateException IO 异常或存在失败项
     */
    private void bulkUpsert(Long hospitalId, Long documentId, List<BulkOperation> operations) {
        BulkResponse response;
        try {
            response = client.bulk(b -> b
                    .index(config.getIndexName())
                    .operations(operations));
        } catch (IOException e) {
            throw new IllegalStateException("知识切片批量写入失败（旧切片保持不变）: hospitalId="
                    + hospitalId + ", documentId=" + documentId, e);
        }
        if (!response.errors()) {
            return;
        }
        long failed = response.items().stream()
                .filter(item -> item.error() != null)
                .count();
        String details = response.items().stream()
                .filter(item -> item.error() != null)
                .map(item -> item.id() + "[status=" + item.status() + ", type="
                        + item.error().type() + ", reason=" + item.error().reason() + "]")
                .collect(Collectors.joining("; "));
        String message = "知识切片批量写入存在失败项（旧切片保持不变）: hospitalId=" + hospitalId
                + ", documentId=" + documentId + ", failed=" + failed + "/" + operations.size()
                + ", details=" + details;
        log.error(message);
        throw new IllegalStateException(message);
    }

    /**
     * 删除越界旧切片：{@code hospitalId + documentId + chunkIndex >= newChunkCount}
     * <p>
     * 仅在批量写入成功后调用；新切片数以内的旧切片已被确定性 ID 覆盖，因此只需清理尾部。
     * 不在此处刷新，由调用方统一刷新一次。
     *
     * @param hospitalId    医院 ID
     * @param documentId    文档 ID
     * @param newChunkCount 本次写入的切片数（新切片的 chunkIndex 应落在 {@code [0, newChunkCount)}）
     * @throws IllegalStateException 删除请求失败
     */
    private void deleteStaleChunks(Long hospitalId, Long documentId, int newChunkCount) {
        try {
            client.deleteByQuery(d -> d
                    .index(config.getIndexName())
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("hospitalId").value(hospitalId.longValue())))
                            .filter(f -> f.term(t -> t.field("documentId").value(documentId.longValue())))
                            .filter(f -> f.range(r -> r
                                    .untyped(u -> u.field("chunkIndex")
                                            .gte(JsonData.of(newChunkCount))))))));
        } catch (IOException e) {
            throw new IllegalStateException("清理越界知识切片失败: hospitalId=" + hospitalId
                    + ", documentId=" + documentId + ", newChunkCount=" + newChunkCount, e);
        }
    }

    /**
     * 删除文档的全部切片（按 hospitalId + documentId delete-by-query）
     *
     * @param hospitalId 医院 ID（null 时按 no-op 处理）
     * @param documentId 文档 ID（null 时按 no-op 处理）
     * @param chunkCount 该文档已写入的切片数（ES 按条件全量删除，此参数仅保留接口语义）
     */
    @Override
    public void deleteDocument(Long hospitalId, Long documentId, int chunkCount) {
        if (hospitalId == null || documentId == null) {
            log.warn("deleteDocument 忽略空 hospitalId/documentId: hospitalId={}, documentId={}",
                    hospitalId, documentId);
            return;
        }
        try {
            client.deleteByQuery(d -> d
                    .index(config.getIndexName())
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("hospitalId").value(hospitalId.longValue())))
                            .filter(f -> f.term(t -> t.field("documentId").value(documentId.longValue()))))));
        } catch (IOException e) {
            throw new IllegalStateException("删除知识切片失败: hospitalId=" + hospitalId
                    + ", documentId=" + documentId, e);
        }
        refresh();
    }

    /**
     * 混合检索本院知识库片段：BM25 与 kNN 各取候选后 RRF 融合
     *
     * @param hospitalId 医院 ID
     * @param query      检索问题
     * @param topK       片段数上限
     * @param threshold  kNN 相似度阈值（小于等于 0 时不启用过滤）
     * @return 命中片段（按融合得分降序）；入参非法、嵌入失败或 ES 故障时返回空列表
     */
    @Override
    public List<ScoredChunk> search(Long hospitalId, String query, int topK, double threshold) {
        if (hospitalId == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        int candidates = topK * 2;
        List<ScoredChunk> bm25 = searchBm25(hospitalId, query, candidates);

        float[] vector;
        try {
            vector = embeddingModel.embed(query);
        } catch (Exception e) {
            recordEmbeddingFailure("查询嵌入失败，混合检索返回空结果: hospitalId=" + hospitalId, e);
            return List.of();
        }
        if (vector == null || vector.length != dimensions) {
            recordEmbeddingFailure("查询向量维度不匹配: expected=" + dimensions
                    + ", actual=" + (vector == null ? -1 : vector.length));
            return List.of();
        }

        List<ScoredChunk> knn = searchKnn(hospitalId, vector, candidates, threshold);
        return RrfFuser.fuse(bm25, knn, topK, RRF_K);
    }

    /**
     * BM25 关键词检索：content 权重 2、section 权重 1，带医院过滤
     *
     * @param hospitalId 医院 ID
     * @param query      检索问题
     * @param size       返回条数
     * @return 命中片段（ES 相关度降序）；查询失败返回空列表
     */
    private List<ScoredChunk> searchBm25(Long hospitalId, String query, int size) {
        try {
            var response = client.search(s -> s
                    .index(config.getIndexName())
                    .size(size)
                    .source(src -> src.filter(f -> f.excludes("embedding")))
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("hospitalId").value(hospitalId.longValue())))
                            .must(m -> m.multiMatch(mm -> mm
                                    .query(query)
                                    .fields("content^2", "section"))))),
                    Map.class);
            return toChunks(response.hits().hits());
        } catch (Exception e) {
            log.warn("BM25 检索失败，按空结果处理: hospitalId={}, index={}",
                    hospitalId, config.getIndexName(), e);
            return List.of();
        }
    }

    /**
     * kNN 向量检索：在 embedding 上取近邻，带医院过滤与可选相似度阈值
     *
     * @param hospitalId 医院 ID
     * @param vector     查询向量
     * @param size       返回条数（同时作为 k）
     * @param threshold  相似度阈值（小于等于 0 时不启用过滤）
     * @return 命中片段（相似度降序）；查询失败返回空列表
     */
    private List<ScoredChunk> searchKnn(Long hospitalId, float[] vector, int size, double threshold) {
        List<Float> queryVector = toFloatList(vector);
        try {
            var response = client.search(s -> s
                    .index(config.getIndexName())
                    .size(size)
                    .source(src -> src.filter(f -> f.excludes("embedding")))
                    .knn(k -> k
                            .field("embedding")
                            .queryVector(queryVector)
                            .k(size)
                            .numCandidates(Math.max(size * 10, size))
                            .filter(f -> f.term(t -> t.field("hospitalId").value(hospitalId.longValue())))),
                    Map.class);
            List<ScoredChunk> chunks = toChunks(response.hits().hits());
            if (threshold <= 0) {
                return chunks;
            }
            // ES 对 cosine 返回 (1 + cosine) / 2 形式的得分，阈值语义与 simple 实现略有差异，故默认 0.5 仅作粗过滤
            return chunks.stream().filter(chunk -> chunk.score() >= threshold).toList();
        } catch (Exception e) {
            log.warn("kNN 检索失败，按空结果处理: hospitalId={}, index={}",
                    hospitalId, config.getIndexName(), e);
            return List.of();
        }
    }

    /**
     * 将 ES 命中映射为检索片段
     *
     * @param hits ES 命中列表
     * @return 片段列表（保持 ES 返回顺序）
     */
    private List<ScoredChunk> toChunks(List<? extends Hit<?>> hits) {
        List<ScoredChunk> chunks = new ArrayList<>(hits.size());
        for (Hit<?> hit : hits) {
            if (!(hit.source() instanceof Map<?, ?> source)) {
                continue;
            }
            chunks.add(new ScoredChunk(
                    stringValue(source.get("documentName")),
                    longValue(source.get("documentId")),
                    intValue(source.get("chunkIndex"), 0),
                    stringValue(source.get("section")),
                    stringValue(source.get("content")),
                    hit.score() == null ? 0.0 : hit.score()));
        }
        return chunks;
    }

    /**
     * 读取已存在索引的 embedding 维度
     *
     * @return 向量维度；索引或字段缺失时返回 -1
     * @throws IOException ES 请求失败
     */
    private int existingDimensions() throws IOException {
        GetMappingResponse response = client.indices().getMapping(g -> g.index(config.getIndexName()));
        var mapping = response.result().get(config.getIndexName());
        if (mapping == null || mapping.mappings() == null || mapping.mappings().properties() == null) {
            return -1;
        }
        var property = mapping.mappings().properties().get("embedding");
        if (property == null || !property.isDenseVector()) {
            return -1;
        }
        return property.denseVector().dims();
    }

    /**
     * 写入前嵌入单个切片：异常或维度不符时记指标并抛错，避免写入半成品
     *
     * @param text       切片正文
     * @param documentId 文档 ID
     * @param chunkIndex 切片序号
     * @return 向量
     * @throws IllegalStateException 嵌入失败或维度不一致
     */
    private float[] embedForWrite(String text, Long documentId, int chunkIndex) {
        String context = "documentId=" + documentId + ", chunkIndex=" + chunkIndex;
        float[] vector;
        try {
            vector = embeddingModel.embed(text);
        } catch (Exception e) {
            recordEmbeddingFailure("文档切片嵌入失败: " + context, e);
            throw new IllegalStateException("文档切片嵌入失败: " + context, e);
        }
        if (vector == null || vector.length != dimensions) {
            String message = "文档切片向量维度不匹配: " + context + ", expected=" + dimensions
                    + ", actual=" + (vector == null ? -1 : vector.length);
            recordEmbeddingFailure(message);
            throw new IllegalStateException(message);
        }
        return vector;
    }

    /**
     * 记录嵌入失败：计数指标 + 错误日志（日志由调用方补齐异常细节）
     *
     * @param message 失败描述
     */
    private static void recordEmbeddingFailure(String message) {
        Metrics.globalRegistry.counter(EMBEDDING_FAILURE_METRIC).increment();
        log.error(message);
    }

    /**
     * 记录嵌入失败：计数指标 + 错误日志（含异常栈）
     *
     * @param message 失败描述
     * @param cause   异常
     */
    private static void recordEmbeddingFailure(String message, Throwable cause) {
        Metrics.globalRegistry.counter(EMBEDDING_FAILURE_METRIC).increment();
        log.error(message, cause);
    }

    /**
     * 刷新索引使写入/删除立即可见（失败仅告警，不影响主流程）
     */
    private void refresh() {
        try {
            client.indices().refresh(r -> r.index(config.getIndexName()));
        } catch (Exception e) {
            log.warn("刷新知识索引失败（结果可能延迟可见）: index={}", config.getIndexName(), e);
        }
    }

    /**
     * 确定性切片 ID
     *
     * @param documentId 文档 ID
     * @param chunkIndex 切片序号
     * @return documentId:chunkIndex
     */
    private String vectorId(Long documentId, int chunkIndex) {
        return documentId + ":" + chunkIndex;
    }

    /**
     * float[] 转 List&lt;Float&gt;（ES kNN 查询向量与 _source 序列化均使用列表）
     *
     * @param vector 向量
     * @return 列表
     */
    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float value : vector) {
            list.add(value);
        }
        return list;
    }

    /**
     * 字符串字段读取（null 视为空串）
     *
     * @param value 原始值
     * @return 字符串值
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 长整型字段读取（兼容 Number 与字符串，非法值回退 0）
     *
     * @param value 原始值
     * @return 长整型值
     */
    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非法字段按缺省值处理
            }
        }
        return 0L;
    }

    /**
     * 整型字段读取（兼容 Number 与字符串，非法值回退缺省值）
     *
     * @param value        原始值
     * @param defaultValue 缺省值
     * @return 整型值
     */
    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非法字段按缺省值处理
            }
        }
        return defaultValue;
    }
}
