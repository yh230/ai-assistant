package com.aiassistant.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.ModelRequest;

import java.util.List;

/**
 * 可用性感知的嵌入模型包装：本地模型加载失败时降级为不可用实例，避免阻断应用启动
 * <p>
 * 可用时所有调用原样委托给真实模型；不可用时 {@code call/embed} 统一抛出
 * {@link IllegalStateException}，由调用方（知识库检索/摄取）按降级或失败重试处理；
 * {@link #dimensions()} 在不可用时返回 0，供启动期维度探测安全通过。
 *
 * @author yh
 * @date 2026-10-19
 */
public class AvailabilityAwareEmbeddingModel implements EmbeddingModel {

    /** 真实嵌入模型（不可用时为空） */
    private final EmbeddingModel delegate;

    /** 模型是否可用 */
    private final boolean available;

    /** 不可用原因（可用时为空） */
    private final String error;

    /**
     * 构造可用实例
     *
     * @param delegate 真实嵌入模型
     */
    public AvailabilityAwareEmbeddingModel(EmbeddingModel delegate) {
        this(delegate, delegate != null, null);
    }

    /**
     * 构造不可用实例
     *
     * @param delegate 真实嵌入模型（加载失败时可为空）
     * @param error    不可用原因
     */
    public AvailabilityAwareEmbeddingModel(EmbeddingModel delegate, String error) {
        this(delegate, false, error);
    }

    /**
     * 全参构造
     *
     * @param delegate  真实嵌入模型
     * @param available 是否可用
     * @param error     不可用原因
     */
    private AvailabilityAwareEmbeddingModel(EmbeddingModel delegate, boolean available, String error) {
        this.delegate = delegate;
        this.available = available;
        this.error = error;
    }

    /**
     * 嵌入模型是否可用
     *
     * @return 模型加载成功且可服务时返回 true
     */
    public boolean isAvailable() {
        return available;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        ensure();
        return delegate.call(request);
    }

    @Override
    public float[] embed(Document document) {
        ensure();
        return delegate.embed(document);
    }

    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options, BatchingStrategy batchingStrategy) {
        ensure();
        return delegate.embed(documents, options, batchingStrategy);
    }

    @Override
    public int dimensions() {
        return available ? delegate.dimensions() : 0;
    }

    /**
     * 校验模型可用性
     *
     * @throws IllegalStateException 模型不可用
     */
    private void ensure() {
        if (!available) {
            throw new IllegalStateException("embedding 模型不可用: " + error);
        }
    }
}
