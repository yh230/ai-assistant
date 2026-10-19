package com.aiassistant.rag;

import com.aiassistant.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SimpleKnowledgeStore 单元测试：写入、替换、删除、医院隔离与持久化
 *
 * @author yh
 * @date 2026-10-19
 */
class SimpleKnowledgeStoreTest {

    /** 固定嵌入向量（查询与切片同向量，余弦相似度为 1） */
    private static final float[] VECTOR = new float[]{1f, 0f, 0f};

    /** 测试用向量目录（每用例独立，避免脏文件干扰） */
    @TempDir
    Path vectorDir;

    /** 模拟嵌入模型 */
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(Document.class))).thenReturn(VECTOR);
        when(embeddingModel.embed(anyString())).thenReturn(VECTOR);
    }

    @Test
    void upsertAndSearchMapsMetadata() {
        SimpleKnowledgeStore store = store();

        Document chunk = Document.builder().id("1:0").text("门诊时间 8:00-17:00")
                .metadata(Map.of("source", "a.md", "documentId", 1L,
                        "chunkIndex", 0, "section", "门诊"))
                .build();
        store.upsertDocument(1L, 1L, "a.md", 0, List.of(chunk));

        List<KnowledgeStore.ScoredChunk> hits = store.search(1L, "门诊时间", 3, 0.0);

        assertEquals(1, hits.size());
        KnowledgeStore.ScoredChunk hit = hits.get(0);
        assertEquals("a.md", hit.source());
        assertEquals(1L, hit.documentId());
        assertEquals(0, hit.chunkIndex());
        assertEquals("门诊", hit.section());
        assertEquals("门诊时间 8:00-17:00", hit.content());
        assertEquals(1.0, hit.score(), 1e-6);
    }

    @Test
    void upsertReplacesPreviousChunks() {
        SimpleKnowledgeStore store = store();
        store.upsertDocument(1L, 1L, "a.md", 0, List.of(
                chunk("旧内容一"), chunk("旧内容二"), chunk("旧内容三")));

        store.upsertDocument(1L, 1L, "a.md", 3, List.of(chunk("新内容")));

        List<KnowledgeStore.ScoredChunk> hits = store.search(1L, "任意问题", 10, 0.0);
        assertEquals(1, hits.size());
        assertEquals("新内容", hits.get(0).content());
        assertEquals(0, hits.get(0).chunkIndex());
    }

    @Test
    void deleteDocumentRemovesAllChunks() {
        SimpleKnowledgeStore store = store();
        store.upsertDocument(2L, 9L, "b.md", 0, List.of(chunk("第一段"), chunk("第二段")));
        assertTrue(Files.exists(vectorDir.resolve("hospital_2.json")));

        store.deleteDocument(2L, 9L, 2);

        assertTrue(store.search(2L, "任意问题", 10, 0.0).isEmpty());
    }

    @Test
    void searchIsIsolatedByHospital() {
        SimpleKnowledgeStore store = store();
        store.upsertDocument(1L, 1L, "a.md", 0, List.of(chunk("仅医院一可见")));

        assertFalse(store.search(1L, "任意问题", 10, 0.0).isEmpty());
        assertTrue(store.search(2L, "任意问题", 10, 0.0).isEmpty());
    }

    @Test
    void searchLoadsPersistedFileOnNewInstance() {
        store().upsertDocument(3L, 5L, "c.md", 0, List.of(chunk("重启后仍可检索")));

        SimpleKnowledgeStore reopened = store();

        List<KnowledgeStore.ScoredChunk> hits = reopened.search(3L, "任意问题", 10, 0.0);
        assertEquals(1, hits.size());
        assertEquals("重启后仍可检索", hits.get(0).content());
    }

    @Test
    void upsertEmptyChunksIsNoOp() {
        SimpleKnowledgeStore store = store();

        assertDoesNotThrow(() -> store.upsertDocument(4L, 6L, "d.md", 0, List.of()));

        assertTrue(store.search(4L, "任意问题", 10, 0.0).isEmpty());
    }

    @Test
    void upsertFillsMissingMetadataFromArguments() {
        SimpleKnowledgeStore store = store();
        Document bare = new Document("无元数据切片");

        store.upsertDocument(5L, 7L, "guide.md", 0, List.of(bare));

        List<KnowledgeStore.ScoredChunk> hits = store.search(5L, "任意问题", 10, 0.0);
        assertEquals(1, hits.size());
        KnowledgeStore.ScoredChunk hit = hits.get(0);
        assertEquals("guide.md", hit.source());
        assertEquals(7L, hit.documentId());
        assertEquals(0, hit.chunkIndex());
        assertEquals("", hit.section());
        assertEquals("无元数据切片", hit.content());
    }

    /**
     * 构造指向当前临时目录的存储实例
     *
     * @return simple 存储实例
     */
    private SimpleKnowledgeStore store() {
        AiProperties props = new AiProperties();
        props.getRag().setVectorPath(vectorDir.toString());
        props.getRag().setStore("simple");
        return new SimpleKnowledgeStore(embeddingModel, props);
    }

    /**
     * 构造带完整元数据的切片
     *
     * @param text 切片正文
     * @return 切片
     */
    private Document chunk(String text) {
        int index = Math.abs(text.hashCode() % 1000);
        return Document.builder().id("1:" + index).text(text)
                .metadata(Map.of("source", "a.md", "documentId", 1L,
                        "chunkIndex", index, "section", "门诊"))
                .build();
    }
}
