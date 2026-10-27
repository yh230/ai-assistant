package com.aiassistant.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置属性默认值回归测试（不依赖 Nacos，纯 POJO 默认值）。
 *
 * @author yh
 * @date 2026-10-19
 */
class AiPropertiesTest {

    @Test
    void chatDefaultsAreSafe() {
        AiProperties props = new AiProperties();
        assertEquals(60, props.getChat().getIdleTimeoutSeconds());
        assertEquals(180, props.getChat().getOverallTimeoutSeconds());
        assertEquals(3000, props.getChat().getContextTokenBudget());
        assertEquals(4000, props.getChat().getMaxInputChars());
        assertEquals(5, props.getChat().getToolMaxIterations());
        assertEquals(5, props.getChat().getToolTimeoutSeconds());
        assertEquals(1024, props.getChat().getMaxTokens());
        assertEquals(10, props.getChat().getHttpConnectTimeoutSeconds());
        assertEquals(120, props.getChat().getHttpReadTimeoutSeconds());
        assertEquals(32, props.getChat().getToolExecutorThreads());
        assertEquals(300, props.getChat().getToolTokenReserve());
        assertEquals("simple", props.getRag().getStore());
        assertEquals(0.15, props.getRag().getChunkOverlap());
    }

    @Test
    void ragDefaultsAreSafe() {
        AiProperties props = new AiProperties();
        assertEquals(384, props.getRag().getEmbeddingDimensions());
        assertEquals(0.35, props.getRag().getGroundednessThreshold());
        assertEquals("ai_knowledge_chunk_v1", props.getRag().getElasticsearch().getIndexName());
        assertEquals("cjk_ngram", props.getRag().getElasticsearch().getAnalyzer());
    }

    @Test
    void assistantFallbackDefaults() {
        AssistantProperties props = new AssistantProperties();
        assertTrue(props.getFallbackModelIds().isEmpty());
        assertEquals(0.7, props.getSceneTemperature().get("chat"));
        assertEquals(0.2, props.getSceneTemperature().get("triage"));
        assertEquals(0.2, props.getSceneTemperature().get("rag"));
    }
}
