package com.aiassistant.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KnowledgeIndexMapping 单元测试：合法 JSON、向量字段维度、检索字段与 ngram 分词器配置
 *
 * @author yh
 * @date 2026-10-19
 */
class KnowledgeIndexMappingTest {

    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mappingContainsDenseVectorAndHospitalFilter() throws Exception {
        String json = KnowledgeIndexMapping.json(384, "cjk_ngram");
        assertTrue(json.contains("\"dims\":384"));
        assertTrue(json.contains("hospitalId"));
        assertTrue(json.contains("embedding"));

        JsonNode embedding = properties(json).path("embedding");
        assertEquals("dense_vector", embedding.path("type").asText());
        assertEquals(384, embedding.path("dims").asInt());
        assertTrue(embedding.path("index").asBoolean());
        assertEquals("cosine", embedding.path("similarity").asText());
    }

    @Test
    void mappingDeclaresAllRetrievalFields() throws Exception {
        JsonNode properties = properties(KnowledgeIndexMapping.json(768, "cjk_ngram"));

        assertEquals("long", properties.path("hospitalId").path("type").asText());
        assertEquals("long", properties.path("documentId").path("type").asText());
        assertEquals("keyword", properties.path("documentName").path("type").asText());
        assertEquals("integer", properties.path("chunkIndex").path("type").asText());
        assertEquals("text", properties.path("section").path("type").asText());
        assertEquals("text", properties.path("content").path("type").asText());
        assertEquals("cjk_ngram", properties.path("section").path("analyzer").asText());
        assertEquals("cjk_ngram", properties.path("content").path("analyzer").asText());
        assertEquals(768, properties.path("embedding").path("dims").asInt());
    }

    @Test
    void analyzerUsesConfiguredNameAndNgramTokenizer() throws Exception {
        JsonNode root = objectMapper.readTree(KnowledgeIndexMapping.json(384, "hospital_ngram"));
        JsonNode analysis = root.path("settings").path("analysis");

        JsonNode analyzer = analysis.path("analyzer").path("hospital_ngram");
        assertEquals("custom", analyzer.path("type").asText());
        assertEquals("hospital_ngram_tokenizer", analyzer.path("tokenizer").asText());

        JsonNode tokenizer = analysis.path("tokenizer").path("hospital_ngram_tokenizer");
        assertEquals("ngram", tokenizer.path("type").asText());
        assertEquals(2, tokenizer.path("min_gram").asInt());
        assertEquals(3, tokenizer.path("max_gram").asInt());
        assertEquals(2, tokenizer.path("token_chars").size());
        assertEquals("letter", tokenizer.path("token_chars").get(0).asText());
        assertEquals("digit", tokenizer.path("token_chars").get(1).asText());

        assertEquals("hospital_ngram", properties(root).path("content").path("analyzer").asText());
    }

    @Test
    void fallsBackToDefaultAnalyzerForBlankName() throws Exception {
        JsonNode root = objectMapper.readTree(KnowledgeIndexMapping.json(384, " "));
        assertTrue(root.path("settings").path("analysis").path("analyzer").has("cjk_ngram"));
    }

    @Test
    void sanitizesAnalyzerNameToKeepJsonValid() throws Exception {
        JsonNode root = objectMapper.readTree(KnowledgeIndexMapping.json(384, "bad\"name"));
        assertTrue(root.path("settings").path("analysis").path("analyzer").has("bad_name"));
    }

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeIndexMapping.json(0, "cjk_ngram"));
    }

    /** 解析 mappings.properties */
    private JsonNode properties(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        return properties(root);
    }

    /** 取 mappings.properties 节点 */
    private JsonNode properties(JsonNode root) {
        return root.path("mappings").path("properties");
    }
}
