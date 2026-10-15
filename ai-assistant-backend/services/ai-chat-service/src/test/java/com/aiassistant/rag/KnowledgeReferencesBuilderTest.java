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

    private Document doc(String source, String text) {
        return new Document(text, Map.of("source", source));
    }

    @Test
    void shouldDedupeBySourceAndKeepRankOrder() {
        String json = builder.build(List.of(
                doc("a.md", "AAA"), doc("b.md", "BBB"), doc("a.md", "AAA2")), 3);

        int posA = json.indexOf("a.md");
        int posB = json.indexOf("b.md");
        assertTrue(posA >= 0 && posB >= 0);
        assertTrue(posA < posB);
        assertFalse(json.contains("AAA2"));
    }

    @Test
    void shouldCapAtMaxReferences() {
        String json = builder.build(List.of(
                doc("a.md", "1"), doc("b.md", "2"), doc("c.md", "3")), 2);

        assertTrue(json.contains("a.md"));
        assertTrue(json.contains("b.md"));
        assertFalse(json.contains("c.md"));
    }

    @Test
    void shouldTruncateSnippetAndCollapseWhitespace() {
        String longText = "x".repeat(80) + "\n第二行";
        String json = builder.build(List.of(doc("a.md", longText)), 1);

        assertTrue(json.contains("x".repeat(60) + "…"));
        assertFalse(json.contains("\n"));
    }

    @Test
    void shouldReturnEmptyForNoHitsOrInvalidLimit() {
        assertEquals("", builder.build(List.of(), 3));
        assertEquals("", builder.build(null, 3));
        assertEquals("", builder.build(List.of(doc("a.md", "1")), 0));
    }

    @Test
    void shouldSkipHitsWithoutSource() {
        assertEquals("", builder.build(List.of(new Document("no-source")), 3));
    }
}
