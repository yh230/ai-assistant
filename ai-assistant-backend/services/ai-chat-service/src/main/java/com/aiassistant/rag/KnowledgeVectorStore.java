package com.aiassistant.rag;

import com.aiassistant.config.AiProperties;
import com.aiassistant.entity.KnowledgeDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 知识库向量存储：按医院分文件的 SimpleVectorStore 封装
 * <p>
 * 每医院一个向量文件（hospital_{id}.json），启动加载、写后落盘；检索仅查本院文件，
 * 天然实现医院级数据隔离。删除文档走全量重建（文档量级小，简单可靠）。
 *
 * @author yh
 * @date 2026-10-13
 */
@Slf4j
@Component
public class KnowledgeVectorStore {

    /** 嵌入模型（T17 本地 ONNX Bean） */
    private final EmbeddingModel embeddingModel;

    /** 文档切片器（切片大小与检索配置同源） */
    private final KnowledgeChunker knowledgeChunker;

    /** 向量文件目录（Nacos ai.rag.vector-path 下发） */
    private final Path vectorDir;

    /** 医院向量表缓存（key=医院 ID） */
    private final Map<Long, SimpleVectorStore> stores = new ConcurrentHashMap<>();

    /**
     * 构造器注入：初始化向量目录并加载存量向量文件
     *
     * @param embeddingModel 嵌入模型
     * @param aiProperties   AI 配置（向量目录与切片大小）
     */
    public KnowledgeVectorStore(EmbeddingModel embeddingModel, AiProperties aiProperties) {
        this.embeddingModel = embeddingModel;
        this.knowledgeChunker = new KnowledgeChunker(aiProperties.getRag().getChunkSize());
        this.vectorDir = Path.of(aiProperties.getRag().getVectorPath());
        createVectorDir();
        loadAll();
    }

    /**
     * 追加切片并落盘
     *
     * @param hospitalId 医院 ID
     * @param chunks     切片列表
     */
    public synchronized void addDocuments(Long hospitalId, List<Document> chunks) {
        SimpleVectorStore store = storeOf(hospitalId);
        store.add(chunks);
        store.save(fileOf(hospitalId).toFile());
    }

    /**
     * 按该医院剩余文档全量重建向量文件
     *
     * @param hospitalId    医院 ID
     * @param remainingDocs 剩余文档（含全文 content）
     */
    public synchronized void rebuild(Long hospitalId, List<KnowledgeDocument> remainingDocs) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        for (KnowledgeDocument doc : remainingDocs) {
            store.add(knowledgeChunker.split(doc.getName(), doc.getContent()));
        }
        store.save(fileOf(hospitalId).toFile());
        stores.put(hospitalId, store);
    }

    /**
     * 检索本院知识库片段
     *
     * @param hospitalId 医院 ID
     * @param query      检索问题
     * @param topK       片段数上限
     * @param threshold  相似度阈值
     * @return 命中片段；本院无向量数据返回空列表
     */
    public List<Document> search(Long hospitalId, String query, int topK, double threshold) {
        SimpleVectorStore store = stores.get(hospitalId);
        if (store == null) {
            return List.of();
        }
        return store.similaritySearch(SearchRequest.builder()
                .query(query).topK(topK).similarityThreshold(threshold).build());
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

    /** 启动加载全部医院向量文件 */
    private void loadAll() {
        try {
            if (!Files.exists(vectorDir)) {
                return;
            }
            try (Stream<Path> files = Files.list(vectorDir)) {
                files.filter(p -> p.getFileName().toString().startsWith("hospital_"))
                        .forEach(p -> {
                            long id = parseHospitalId(p);
                            if (id > 0) {
                                storeOf(id);
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("加载向量目录失败: {}", vectorDir, e);
        }
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
     * 从向量文件名解析医院 ID
     *
     * @param p 向量文件路径
     * @return 医院 ID；文件名不合法返回 -1
     */
    private long parseHospitalId(Path p) {
        String name = p.getFileName().toString();
        try {
            return Long.parseLong(name.substring("hospital_".length(), name.length() - ".json".length()));
        } catch (Exception e) {
            return -1;
        }
    }
}
