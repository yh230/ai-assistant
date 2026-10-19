package com.aiassistant.rag;

import com.aiassistant.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库向量存储 simple 实现：按医院分文件的 {@link SimpleVectorStore} 封装
 * <p>
 * 每医院一个向量文件（hospital_{id}.json），首次访问时按需加载、写后落盘；检索仅查本院文件，
 * 医院级数据隔离由文件名与缓存键共同保证。文档切片使用确定性 ID（documentId:chunkIndex），
 * 更新走“按旧切片数删除 + 追加”，删除走“按切片数删除”，避免整库重建。
 *
 * @author yh
 * @date 2026-10-19
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.rag.store", havingValue = "simple", matchIfMissing = true)
public class SimpleKnowledgeStore implements KnowledgeStore {

    /** 嵌入模型 */
    private final EmbeddingModel embeddingModel;

    /** 向量文件目录（Nacos ai.rag.vector-path 下发） */
    private final Path vectorDir;

    /** 医院向量表缓存（key=医院 ID） */
    private final Map<Long, SimpleVectorStore> stores = new ConcurrentHashMap<>();

    /**
     * 构造器注入：初始化向量目录
     *
     * @param embeddingModel 嵌入模型
     * @param props          AI 配置（读取向量目录）
     */
    public SimpleKnowledgeStore(EmbeddingModel embeddingModel, AiProperties props) {
        this.embeddingModel = embeddingModel;
        this.vectorDir = Path.of(props.getRag().getVectorPath());
        createVectorDir();
    }

    /**
     * 幂等写入文档切片：先按旧切片数删除确定性 ID，再追加新切片并落盘
     *
     * @param hospitalId         医院 ID
     * @param documentId         文档 ID
     * @param documentName       文档名（写入 metadata.source）
     * @param previousChunkCount 该文档此前已写入的切片数
     * @param chunks             新切片列表
     */
    @Override
    public synchronized void upsertDocument(Long hospitalId, Long documentId, String documentName,
                                            int previousChunkCount, List<Document> chunks) {
        Objects.requireNonNull(hospitalId, "hospitalId");
        Objects.requireNonNull(documentId, "documentId");
        List<Document> safeChunks = chunks == null ? List.of() : chunks;

        SimpleVectorStore store = storeOf(hospitalId);
        List<String> oldIds = ids(documentId, previousChunkCount);
        if (!oldIds.isEmpty()) {
            store.delete(oldIds);
        }
        List<Document> normalized = normalize(documentId, documentName, safeChunks);
        if (!normalized.isEmpty()) {
            store.add(normalized);
        }
        save(hospitalId, store);
    }

    /**
     * 删除文档的全部切片并落盘
     *
     * @param hospitalId 医院 ID
     * @param documentId 文档 ID
     * @param chunkCount 该文档已写入的切片数
     */
    @Override
    public synchronized void deleteDocument(Long hospitalId, Long documentId, int chunkCount) {
        Objects.requireNonNull(hospitalId, "hospitalId");
        Objects.requireNonNull(documentId, "documentId");

        SimpleVectorStore store = storeOf(hospitalId);
        List<String> targetIds = ids(documentId, chunkCount);
        if (!targetIds.isEmpty()) {
            store.delete(targetIds);
        }
        save(hospitalId, store);
    }

    /**
     * 检索本院知识库片段
     *
     * @param hospitalId 医院 ID
     * @param query      检索问题
     * @param topK       片段数上限
     * @param threshold  相似度阈值
     * @return 命中片段（按相关度降序）；本院无向量数据返回空列表
     */
    @Override
    public List<ScoredChunk> search(Long hospitalId, String query, int topK, double threshold) {
        SimpleVectorStore store = stores.get(hospitalId);
        if (store == null) {
            // 应用重启后尚未发生写入时，按存量文件懒加载，避免检索结果为空
            if (!Files.exists(fileOf(hospitalId))) {
                return List.of();
            }
            store = storeOf(hospitalId);
        }
        return store.similaritySearch(SearchRequest.builder()
                        .query(query).topK(topK).similarityThreshold(threshold).build())
                .stream()
                .map(this::toScoredChunk)
                .toList();
    }

    /**
     * 规范化切片：强制确定性 ID 与检索所需的 metadata，缺失字段按入参补齐
     *
     * @param documentId   文档 ID
     * @param documentName 文档名
     * @param chunks       原始切片
     * @return 规范化切片
     */
    private List<Document> normalize(Long documentId, String documentName, List<Document> chunks) {
        List<Document> normalized = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("source", documentName == null ? "" : documentName);
            metadata.put("documentId", documentId);
            metadata.put("chunkIndex", i);
            if (metadata.get("section") == null) {
                metadata.put("section", "");
            }
            normalized.add(Document.builder()
                    .id(vectorId(documentId, i))
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build());
        }
        return normalized;
    }

    /**
     * 将 Spring AI 文档映射为检索命中片段
     *
     * @param document 命中文档
     * @return 命中片段
     */
    private ScoredChunk toScoredChunk(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new ScoredChunk(
                stringValue(metadata.get("source")),
                longValue(metadata.get("documentId")),
                intValue(metadata.get("chunkIndex")),
                stringValue(metadata.get("section")),
                document.getText(),
                document.getScore() == null ? 0.0 : document.getScore());
    }

    /**
     * 生成确定性切片 ID 列表
     *
     * @param documentId 文档 ID
     * @param count      切片数
     * @return 切片 ID 列表（documentId:0..count-1）
     */
    private List<String> ids(Long documentId, int count) {
        List<String> ids = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            ids.add(vectorId(documentId, i));
        }
        return ids;
    }

    /**
     * 确定性切片 ID
     *
     * @param documentId 文档 ID
     * @param chunkIndex 切片序号
     * @return 切片 ID
     */
    private String vectorId(Long documentId, int chunkIndex) {
        return documentId + ":" + chunkIndex;
    }

    /**
     * 取本院向量存储（缺失时创建并按存量文件加载）
     *
     * @param hospitalId 医院 ID
     * @return 本院向量存储
     */
    private SimpleVectorStore storeOf(Long hospitalId) {
        return stores.computeIfAbsent(hospitalId, id -> {
            SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
            File file = fileOf(id).toFile();
            if (file.exists()) {
                try {
                    store.load(file);
                } catch (Exception e) {
                    log.warn("加载向量文件失败: {}", file, e);
                }
            }
            return store;
        });
    }

    /**
     * 落盘本院向量文件
     *
     * @param hospitalId 医院 ID
     * @param store      本院向量存储
     */
    private void save(Long hospitalId, SimpleVectorStore store) {
        store.save(fileOf(hospitalId).toFile());
    }

    /** 确保向量目录存在（首次落盘前目录缺失会导致保存失败） */
    private void createVectorDir() {
        try {
            Files.createDirectories(vectorDir);
        } catch (IOException e) {
            log.warn("创建向量目录失败: {}", vectorDir, e);
        }
    }

    /**
     * 本院向量文件路径
     *
     * @param hospitalId 医院 ID
     * @return 向量文件路径
     */
    private Path fileOf(Long hospitalId) {
        return vectorDir.resolve("hospital_" + hospitalId + ".json");
    }

    /**
     * 字符串元数据读取（null 视为空串）
     *
     * @param value 原始值
     * @return 字符串值
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 长整型元数据读取（兼容 Number 与字符串，非法值回退 0）
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
                // 非法元数据按缺省值处理
            }
        }
        return 0L;
    }

    /**
     * 整型元数据读取（兼容 Number 与字符串，非法值回退 0）
     *
     * @param value 原始值
     * @return 整型值
     */
    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非法元数据按缺省值处理
            }
        }
        return 0;
    }
}
