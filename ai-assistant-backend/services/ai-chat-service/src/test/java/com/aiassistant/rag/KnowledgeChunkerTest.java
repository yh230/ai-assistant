package com.aiassistant.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Test
    void zeroOverlapDoesNotRepeatPreviousChunk() {
        KnowledgeChunker noOverlap = new KnowledgeChunker(10, 0.0);
        String first = "一二三四五六七八";
        String second = "九十甲乙丙丁戊己";

        List<Document> docs = noOverlap.split(5L, "无重叠.md", first + "\n\n" + second);

        assertEquals(2, docs.size());
        assertEquals(first, docs.get(0).getText());
        assertEquals(second, docs.get(1).getText());
    }

    @Test
    void oversizedOverlapIsClampedAndNeverDuplicatesWholeChunk() {
        // overlap=5.0 应被收敛为 0.5，即最多 10/2=5 个 token
        KnowledgeChunker hugeOverlap = new KnowledgeChunker(10, 5.0);
        String first = "一二三四五六七八";
        String second = "九十甲乙丙丁戊己";

        List<Document> docs = hugeOverlap.split(6L, "大重叠.md", first + "\n\n" + second);

        assertEquals(2, docs.size());
        assertEquals(first, docs.get(0).getText());
        String secondChunk = docs.get(1).getText();
        assertTrue(secondChunk.startsWith("四五六七八"));
        assertTrue(secondChunk.contains(second));
        assertNotEquals(first, secondChunk);
    }

    @Test
    void chunkShorterThanOverlapIsNotCopiedWholesale() {
        KnowledgeChunker hugeOverlap = new KnowledgeChunker(10, 1.0);
        String first = "一二三四";
        String second = "五六七八九十甲乙";

        List<Document> docs = hugeOverlap.split(7L, "短片.md", first + "\n\n" + second);

        assertEquals(2, docs.size());
        assertEquals(first, docs.get(0).getText());
        assertEquals(second, docs.get(1).getText());
    }

    @Test
    void everyParagraphIsPreservedInSomeChunk() {
        KnowledgeChunker small = new KnowledgeChunker(12, 0.15);
        String[] paragraphs = {
                "门诊时间为周一至周五。",
                "挂号可通过公众号办理。",
                "缴费支持自助机与窗口。",
                "取药请到一层药房窗口。"
        };

        List<Document> docs = small.split(8L, "全文.md", String.join("\n\n", paragraphs));

        assertTrue(docs.size() > 1);
        for (String paragraph : paragraphs) {
            assertTrue(docs.stream().anyMatch(doc -> doc.getText().contains(paragraph)),
                    "段落被丢弃: " + paragraph);
        }
    }

    @Test
    void nonPositiveChunkSizeFallsBackToOneToken() {
        KnowledgeChunker zero = new KnowledgeChunker(0);
        KnowledgeChunker negative = new KnowledgeChunker(-5, 0.5);

        List<Document> docs = zero.split(9L, "小片.md", "门诊时间");

        assertFalse(docs.isEmpty());
        docs.forEach(doc -> assertFalse(doc.getText().isBlank()));
        assertEquals("门诊时间", docs.stream().map(Document::getText).reduce("", String::concat));
        assertFalse(negative.split(10L, "小片.md", "门诊时间").isEmpty());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacyOverloadGeneratesUniqueDocumentIdsPerCall() {
        List<Document> first = chunker.split("a.md", "内容。");
        List<Document> second = chunker.split("a.md", "内容。");

        Set<Long> documentIds = new HashSet<>();
        first.forEach(doc -> documentIds.add(((Number) doc.getMetadata().get("documentId")).longValue()));
        second.forEach(doc -> documentIds.add(((Number) doc.getMetadata().get("documentId")).longValue()));

        assertEquals(2, documentIds.size());
        assertTrue(documentIds.stream().allMatch(id -> id >= 0));
        assertNotEquals(first.get(0).getId(), second.get(0).getId());
    }
}
