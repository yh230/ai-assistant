package com.aiassistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库切片器单元测试（纯本地，不加载嵌入模型）
 *
 * @author yh
 * @date 2026-10-13
 */
class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker(800);

    @Test
    void shortTextProducesSingleChunk() {
        List<Document> docs = chunker.split("门诊须知.md", "门诊时间为周一至周五 8:00-17:00。");
        assertEquals(1, docs.size());
        assertEquals("门诊须知.md", docs.get(0).getMetadata().get("source"));
        assertTrue(docs.get(0).getText().contains("门诊时间"));
    }

    @Test
    void longTextProducesMultipleChunks() {
        String longText = "医院就诊流程说明。".repeat(500);
        List<Document> docs = chunker.split("流程.txt", longText);
        assertTrue(docs.size() > 1);
        docs.forEach(d -> assertFalse(d.getText().isBlank()));
    }
}
