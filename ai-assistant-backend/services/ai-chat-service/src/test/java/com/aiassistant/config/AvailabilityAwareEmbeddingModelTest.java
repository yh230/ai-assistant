package com.aiassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可用性感知嵌入模型包装单测
 *
 * @author yh
 * @date 2026-10-19
 */
class AvailabilityAwareEmbeddingModelTest {

    private final EmbeddingModel delegate = mock(EmbeddingModel.class);

    @Test
    void delegatesAllOperationsWhenAvailable() {
        AvailabilityAwareEmbeddingModel model = new AvailabilityAwareEmbeddingModel(delegate);
        EmbeddingRequest request = new EmbeddingRequest(List.of("问题"),
                EmbeddingOptions.builder().build());
        EmbeddingResponse response = new EmbeddingResponse(List.of());
        when(delegate.call(request)).thenReturn(response);

        assertTrue(model.isAvailable());
        assertSame(response, model.call(request));

        Document document = new Document("正文");
        float[] vector = {1.0f, 2.0f};
        when(delegate.embed(document)).thenReturn(vector);
        assertSame(vector, model.embed(document));

        List<Document> documents = List.of(document);
        EmbeddingOptions options = EmbeddingOptions.builder().build();
        BatchingStrategy batching = mock(BatchingStrategy.class);
        List<float[]> vectors = List.of(vector);
        when(delegate.embed(documents, options, batching)).thenReturn(vectors);
        assertSame(vectors, model.embed(documents, options, batching));

        when(delegate.dimensions()).thenReturn(384);
        assertEquals(384, model.dimensions());
    }

    @Test
    void throwsIllegalStateWhenUnavailable() {
        AvailabilityAwareEmbeddingModel model = new AvailabilityAwareEmbeddingModel(null, "模型加载失败");
        EmbeddingRequest request = new EmbeddingRequest(List.of("问题"),
                EmbeddingOptions.builder().build());

        assertFalse(model.isAvailable());
        IllegalStateException callError = assertThrows(IllegalStateException.class, () -> model.call(request));
        assertEquals("embedding 模型不可用: 模型加载失败", callError.getMessage());
        assertThrows(IllegalStateException.class, () -> model.embed(new Document("正文")));
        assertThrows(IllegalStateException.class, () -> model.embed(List.of(new Document("正文")),
                EmbeddingOptions.builder().build(), mock(BatchingStrategy.class)));
    }

    @Test
    void reportsZeroDimensionsWhenUnavailable() {
        AvailabilityAwareEmbeddingModel model = new AvailabilityAwareEmbeddingModel(null, "模型加载失败");

        assertEquals(0, model.dimensions());
    }
}
