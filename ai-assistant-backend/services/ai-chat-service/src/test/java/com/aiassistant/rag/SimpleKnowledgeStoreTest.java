package com.aiassistant.rag;

import com.aiassistant.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SimpleKnowledgeStore 单元测试：写入、替换、删除、医院隔离、持久化、排序阈值与失败回滚
 *
 * @author yh
 * @date 2026-10-19
 */
class SimpleKnowledgeStoreTest {

    /** 嵌入维度 */
    private static final int DIMENSIONS = 3;

    /** 默认向量（未命中关键词的文本与查询均使用，余弦相似度为 1） */
    private static final float[] DEFAULT_VECTOR = new float[]{1f, 0f, 0f};

    /** 包含 cat 的文本向量 */
    private static final float[] CAT_VECTOR = new float[]{1f, 0f, 0f};

    /** 包含 dog 的文本向量（与 cat 正交，余弦相似度为 0） */
    private static final float[] DOG_VECTOR = new float[]{0f, 1f, 0f};

    /** 测试用向量目录（每用例独立，避免脏文件干扰） */
    @TempDir
    Path vectorDir;

    /** 模拟嵌入模型 */
    private EmbeddingModel embeddingModel;

    /** 所有嵌入重载共享的调用计数，供失败注入用例确定性定位“第二次 upsert” */
    private final AtomicInteger embedInvocations = new AtomicInteger();

    /** 从第几次嵌入调用开始抛错（Integer.MAX_VALUE 表示不注入失败） */
    private volatile int failEmbeddingFrom = Integer.MAX_VALUE;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        embedInvocations.set(0);
        failEmbeddingFrom = Integer.MAX_VALUE;
        // Spring AI 1.0.9 的 SimpleVectorStore 按切片调用 embed(Document)、按查询调用 embed(String)；
        // 其它版本/实现可能走批量重载，因此所有重载与 dimensions() 全部桩掉，
        // 并共享同一个调用计数与失败开关：无论实际走哪个重载，行为都一致。
        doAnswer(invocation -> {
            checkEmbeddingFailure();
            return vectorFor(invocation.getArgument(0, Document.class).getText());
        }).when(embeddingModel).embed(any(Document.class));
        doAnswer(invocation -> {
            checkEmbeddingFailure();
            return vectorFor(invocation.getArgument(0, String.class));
        }).when(embeddingModel).embed(anyString());
        doAnswer(invocation -> {
            checkEmbeddingFailure();
            List<?> texts = invocation.getArgument(0);
            return texts.stream().map(text -> vectorFor(String.valueOf(text))).toList();
        }).when(embeddingModel).embed(anyList());
        doAnswer(invocation -> {
            checkEmbeddingFailure();
            List<?> documents = invocation.getArgument(0);
            return documents.stream()
                    .map(document -> vectorFor(((Document) document).getText()))
                    .toList();
        }).when(embeddingModel).embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class));
        when(embeddingModel.dimensions()).thenReturn(DIMENSIONS);
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
    void deleteMissingIdIsSafeAndKeepsExistingChunks() {
        SimpleKnowledgeStore store = store();
        store.upsertDocument(7L, 70L, "x.md", 0, List.of(chunk("保留内容")));

        assertDoesNotThrow(() -> store.deleteDocument(7L, 999L, 3));

        List<KnowledgeStore.ScoredChunk> hits = store.search(7L, "任意问题", 10, 0.0);
        assertEquals(1, hits.size());
        assertEquals("保留内容", hits.get(0).content());
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

    @Test
    void searchRanksBySimilarityAndAppliesThresholdAndTopK() {
        SimpleKnowledgeStore store = store();
        store.upsertDocument(6L, 60L, "pets.md", 0, List.of(
                chunk("cat food", "宠物"), chunk("dog food", "宠物")));

        // 不同文本得到不同向量：cat 查询仅 cat 切片过阈值
        List<KnowledgeStore.ScoredChunk> catHits = store.search(6L, "cat", 10, 0.5);
        assertEquals(1, catHits.size());
        assertEquals("cat food", catHits.get(0).content());
        assertEquals(1.0, catHits.get(0).score(), 1e-6);

        List<KnowledgeStore.ScoredChunk> dogHits = store.search(6L, "dog", 10, 0.5);
        assertEquals(1, dogHits.size());
        assertEquals("dog food", dogHits.get(0).content());
        assertEquals(1.0, dogHits.get(0).score(), 1e-6);

        // topK 生效：两个切片都过阈值时只返回最相关的一条
        List<KnowledgeStore.ScoredChunk> topOne = store.search(6L, "cat", 1, 0.0);
        assertEquals(1, topOne.size());
        assertEquals("cat food", topOne.get(0).content());
    }

    @Test
    void nullHospitalIdIsSafeNoOp() {
        SimpleKnowledgeStore store = store();

        assertDoesNotThrow(() -> store.upsertDocument(null, 1L, "a.md", 0, List.of(chunk("内容"))));
        assertDoesNotThrow(() -> store.deleteDocument(null, 1L, 1));
        assertTrue(store.search(null, "任意问题", 10, 0.0).isEmpty());
    }

    @Test
    void failedAddKeepsPreviousSearchResult() {
        SimpleKnowledgeStore store = store();
        store.upsertDocument(8L, 80L, "old.md", 0, List.of(chunk("旧内容一"), chunk("旧内容二")));

        // 首次 upsert 完成后，让后续任意一次嵌入调用（即第二次 upsert 使用的任一重载）抛错：
        // 共享计数保证无论 SimpleVectorStore 走 embed(Document)/embed(String)/批量重载都会失败。
        failEmbeddingFrom = embedInvocations.get();
        assertThrows(IllegalStateException.class, () -> store.upsertDocument(
                8L, 80L, "old.md", 2, List.of(chunk("新内容一"), chunk("新内容二"))));

        // 恢复嵌入，使后续检索（embed(String)）可以正常执行
        failEmbeddingFrom = Integer.MAX_VALUE;
        List<KnowledgeStore.ScoredChunk> hits = store.search(8L, "任意问题", 10, 0.0);
        assertEquals(2, hits.size());
        assertTrue(hits.stream().anyMatch(hit -> "旧内容一".equals(hit.content())));
        assertTrue(hits.stream().anyMatch(hit -> "旧内容二".equals(hit.content())));
        assertTrue(hits.stream().noneMatch(hit -> hit.content().startsWith("新内容")));
    }

    /**
     * 记录一次嵌入调用，并在超过失败阈值时抛错（所有重载共用）
     */
    private void checkEmbeddingFailure() {
        if (embedInvocations.incrementAndGet() > failEmbeddingFrom) {
            throw new IllegalStateException("embedding failed");
        }
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
     * 按文本关键词返回不同向量，用于验证“不同文本 → 不同嵌入 → 排序/阈值”
     *
     * @param text 文本
     * @return 嵌入向量
     */
    private float[] vectorFor(String text) {
        String value = text == null ? "" : text;
        if (value.contains("dog")) {
            return DOG_VECTOR;
        }
        if (value.contains("cat")) {
            return CAT_VECTOR;
        }
        return DEFAULT_VECTOR;
    }

    /**
     * 构造带完整元数据的切片（section 默认“门诊”）
     *
     * @param text 切片正文
     * @return 切片
     */
    private Document chunk(String text) {
        return chunk(text, "门诊");
    }

    /**
     * 构造带完整元数据的切片
     *
     * @param text    切片正文
     * @param section 章节名
     * @return 切片
     */
    private Document chunk(String text, String section) {
        return Document.builder().id("chunk-" + Math.abs(text.hashCode())).text(text)
                .metadata(Map.of("source", "a.md", "documentId", 1L,
                        "chunkIndex", 0, "section", section))
                .build();
    }
}
