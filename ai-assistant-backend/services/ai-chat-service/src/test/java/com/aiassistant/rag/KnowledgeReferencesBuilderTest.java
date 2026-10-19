package com.aiassistant.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库引用来源构建器测试
 *
 * @author yh
 * @date 2026-10-14
 */
class KnowledgeReferencesBuilderTest {

    private final KnowledgeReferencesBuilder builder = new KnowledgeReferencesBuilder(new ObjectMapper());

    private KnowledgeStore.ScoredChunk chunk(long documentId, int chunkIndex, String source,
                                             String section, String content, double score) {
        return new KnowledgeStore.ScoredChunk(source, documentId, chunkIndex, section, content, score);
    }

    @Test
    void shouldDedupeByChunkAndKeepRankOrder() {
        String json = builder.buildFromChunks(List.of(
                chunk(1L, 0, "a.md", "门诊", "AAA", 0.9),
                chunk(2L, 0, "b.md", "挂号", "BBB", 0.8),
                chunk(1L, 0, "a.md", "门诊", "AAA-duplicate", 0.7)), 3);

        int posA = json.indexOf("a.md");
        int posB = json.indexOf("b.md");
        assertTrue(posA >= 0 && posB >= 0);
        assertTrue(posA < posB);
        assertFalse(json.contains("AAA-duplicate"));
        assertTrue(json.contains("\"section\":\"门诊\""));
        assertTrue(json.contains("\"score\":0.9"));
        assertTrue(json.contains("\"snippet\":\"AAA\""));
    }

    @Test
    void keepsDifferentChunksOfSameDocument() {
        String json = builder.buildFromChunks(List.of(
                chunk(1L, 0, "a.md", "门诊", "AAA", 0.9),
                chunk(1L, 1, "a.md", "挂号", "BBB", 0.8)), 3);

        assertEquals(2, json.split("a\\.md", -1).length - 1);
        assertTrue(json.contains("BBB"));
    }

    @Test
    void shouldCapAtMaxReferences() {
        String json = builder.buildFromChunks(List.of(
                chunk(1L, 0, "a.md", "", "1", 0.9),
                chunk(2L, 0, "b.md", "", "2", 0.8),
                chunk(3L, 0, "c.md", "", "3", 0.7)), 2);

        assertTrue(json.contains("a.md"));
        assertTrue(json.contains("b.md"));
        assertFalse(json.contains("c.md"));
    }

    @Test
    void shouldTruncateSnippetAndCollapseWhitespace() {
        String longText = "x".repeat(80) + "\n第二行";
        String json = builder.buildFromChunks(List.of(chunk(1L, 0, "a.md", "", longText, 0.5)), 1);

        assertTrue(json.contains("x".repeat(60) + "…"));
        assertFalse(json.contains("\n"));
    }

    @Test
    void shouldReturnEmptyForNoHitsOrInvalidLimit() {
        assertEquals("", builder.buildFromChunks(List.of(), 3));
        assertEquals("", builder.buildFromChunks(null, 3));
        assertEquals("", builder.buildFromChunks(List.of(chunk(1L, 0, "a.md", "", "1", 0.9)), 0));
    }

    @Test
    void shouldSkipHitsWithoutSource() {
        assertEquals("", builder.buildFromChunks(List.of(chunk(1L, 0, null, "", "1", 0.9)), 3));
        assertEquals("", builder.buildFromChunks(List.of(chunk(1L, 0, "  ", "", "1", 0.9)), 3));
    }

    @Test
    void shouldStillAcceptLegacyDocuments() {
        Document doc = new Document("正文", Map.of("source", "a.md", "section", "门诊"));

        String json = builder.build(List.of(doc), 1);

        assertTrue(json.contains("\"name\":\"a.md\""));
        assertTrue(json.contains("\"section\":\"门诊\""));
        assertTrue(json.contains("\"score\":0.0"));
    }

    @Test
    void shouldSkipLegacyDocumentWithoutSource() {
        assertEquals("", builder.build(List.of(new Document("no-source")), 3));
    }
}
