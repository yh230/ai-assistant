package com.aiassistant.service.support;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * AI 流式指标与响应文本工具：统一记录请求数、首 token 延迟、token 用量与失败原因。
 * <p>
 * 员工侧与患者侧共用同一套指标口径，避免两套服务各写一份流式响应处理逻辑。
 *
 * @author yh
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class AiStreamSupport {

    private final MeterRegistry meterRegistry;

    /**
     * 包装单个候选模型的响应流：记录请求数、用量、首 token 延迟与失败指标。
     *
     * @param modelId 模型ID
     * @param scene   场景标识（chat/triage/rag）
     * @param call    模型调用工厂
     * @return 正文 token 流（空文本已过滤）
     */
    public Flux<String> tokenFlux(String modelId, String scene, Supplier<Flux<ChatResponse>> call) {
        long startNanos = System.nanoTime();
        AtomicBoolean firstToken = new AtomicBoolean(true);
        return Flux.<ChatResponse>defer(() -> {
                    meterRegistry.counter("ai.model.requests", "model", modelId, "scene", scene).increment();
                    return call.get();
                })
                .doOnNext(response -> recordUsage(modelId, scene, response))
                .map(AiStreamSupport::extractText)
                .filter(StringUtils::hasText)
                .doOnNext(token -> {
                    if (firstToken.compareAndSet(true, false)) {
                        meterRegistry.timer("ai.model.latency", "model", modelId)
                                .record(Duration.ofNanos(System.nanoTime() - startNanos));
                    }
                })
                .doOnError(ex -> recordFailure(modelId, scene, failureReason(ex)));
    }

    /**
     * 记录总超时截断计数（model + scene 维度）。
     *
     * @param modelId 模型ID
     * @param scene   场景标识
     */
    public void recordTruncated(String modelId, String scene) {
        meterRegistry.counter("ai.model.truncated", "model", modelId, "scene", scene).increment();
    }

    /**
     * 记录模型失败计数（model + scene + reason 维度）。
     *
     * @param modelId 模型ID
     * @param scene   场景标识
     * @param reason  失败原因标签
     */
    public void recordFailure(String modelId, String scene, String reason) {
        meterRegistry.counter("ai.model.failures", "model", modelId, "scene", scene, "reason", reason)
                .increment();
    }

    /**
     * 从模型响应中提取正文（工具调用等无文本片段返回空串，由调用方过滤）。
     *
     * @param response 模型响应
     * @return 正文文本
     */
    public static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 归一化失败原因标签（异常类型名）。
     *
     * @param ex 异常
     * @return 失败原因标签
     */
    public static String failureReason(Throwable ex) {
        return ex.getClass().getSimpleName();
    }

    /**
     * 记录模型用量指标（提示/补全 token）；流式响应未携带用量时静默跳过。
     *
     * @param modelId  模型ID
     * @param scene    场景标识
     * @param response 模型响应
     */
    private void recordUsage(String modelId, String scene, ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        Integer promptTokens = usage.getPromptTokens();
        if (promptTokens != null && promptTokens > 0) {
            meterRegistry.counter("ai.model.tokens", "model", modelId, "scene", scene, "type", "prompt")
                    .increment(promptTokens);
        }
        Integer completionTokens = usage.getCompletionTokens();
        if (completionTokens != null && completionTokens > 0) {
            meterRegistry.counter("ai.model.tokens", "model", modelId, "scene", scene, "type", "completion")
                    .increment(completionTokens);
        }
    }
}
