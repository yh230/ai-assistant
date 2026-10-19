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

    private final KnowledgeChunker chunker = new KnowledgeChunker(800, 0.15);

    @Test
    void shortTextProducesSingleChunkWithMetadata() {
        List<Document> docs = chunker.split(1L, "门诊须知.md", "门诊时间为周一至周五 8:00-17:00。");

        assertEquals(1, docs.size());
        Document doc = docs.get(0);
        assertEquals("1:0", doc.getId());
        assertEquals("门诊须知.md", doc.getMetadata().get("source"));
        assertEquals(1L, doc.getMetadata().get("documentId"));
        assertEquals(0, doc.getMetadata().get("chunkIndex"));
        assertEquals("", doc.getMetadata().get("section"));
        assertTrue(doc.getText().contains("门诊时间"));
    }

    @Test
    void markdownHeadingsBecomeSectionMetadata() {
        String text = """
                # 门诊须知
                门诊时间为周一至周五。
                ## 挂号方式
                可通过公众号或现场自助机挂号。
                """;

        List<Document> docs = chunker.split(2L, "须知.md", text);

        assertEquals(2, docs.size());
        assertEquals("门诊须知", docs.get(0).getMetadata().get("section"));
        assertEquals("挂号方式", docs.get(1).getMetadata().get("section"));
        assertEquals("2:0", docs.get(0).getId());
        assertEquals("2:1", docs.get(1).getId());
        assertFalse(docs.get(0).getText().contains("# 门诊须知"));
    }

    @Test
    void idsAreDeterministicAndSequential() {
        String text = "医院就诊流程说明。".repeat(500);

        List<Document> first = chunker.split(9L, "流程.txt", text);
        List<Document> second = chunker.split(9L, "流程.txt", text);

        assertTrue(first.size() > 1);
        assertEquals(first.stream().map(Document::getId).toList(),
                second.stream().map(Document::getId).toList());
        for (int i = 0; i < first.size(); i++) {
            assertEquals("9:" + i, first.get(i).getId());
            assertEquals(i, first.get(i).getMetadata().get("chunkIndex"));
            assertEquals("9:" + i, second.get(i).getId());
        }
    }

    @Test
    void longTextProducesMultipleChunks() {
        String longText = "医院就诊流程说明。".repeat(500);

        List<Document> docs = chunker.split(4L, "流程.txt", longText);

        assertTrue(docs.size() > 1);
        docs.forEach(d -> assertFalse(d.getText().isBlank()));
    }

    @Test
    void carriesOverlapTailIntoNextChunk() {
        KnowledgeChunker small = new KnowledgeChunker(20, 0.5);
        String first = "一二三四五六七八九十甲乙";
        String second = "丙丁戊己庚辛壬癸子丑寅卯";
        String text = "# 须知\n" + first + "\n\n" + second;

        List<Document> docs = small.split(3L, "重叠.md", text);

        assertEquals(2, docs.size());
        assertEquals(first, docs.get(0).getText());
        assertTrue(docs.get(1).getText().startsWith("三四五六七八九十甲乙"));
        assertTrue(docs.get(1).getText().contains(second));
    }

    @Test
    void blankTextProducesNoChunks() {
        assertTrue(chunker.split(1L, "a.md", "  \n\n ").isEmpty());
        assertTrue(chunker.split(1L, "a.md", null).isEmpty());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacyOverloadUsesZeroDocumentId() {
        List<Document> docs = chunker.split("a.md", "内容。");

        assertEquals(1, docs.size());
        assertEquals("0:0", docs.get(0).getId());
        assertEquals(0L, docs.get(0).getMetadata().get("documentId"));
    }
}
