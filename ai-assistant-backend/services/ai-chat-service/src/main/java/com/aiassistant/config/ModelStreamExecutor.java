package com.aiassistant.config;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 模型故障转移执行器：按给定模型链顺序尝试，仅当尚未输出任何 token 时才切换到下一个模型。
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>首 token 前可重试</b>：模型调用失败或单模型超时且尚未产生 token 时，自动尝试链中的下一个模型；</li>
 *     <li><b>部分输出后不切换</b>：已向客户端推送 token 后再切换模型会造成内容拼接错乱，故直接透传异常；</li>
 *     <li><b>空 token 不算输出</b>：null/空串（工具调用等无文本片段）先过滤，不置位 emitted，也不向下游透传；</li>
 *     <li><b>全部失败</b>：模型链耗尽后以 {@link ModelChainExhaustedException} 结束，由上层降级本地引擎。</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
public class ModelStreamExecutor {

    /**
     * 按模型链顺序执行流式调用（不设置单模型超时）。
     *
     * @param modelIds 模型链（主模型 + 备用模型，已按优先级排序）
     * @param call     单模型调用工厂（入参为模型ID）
     * @return token 流
     */
    public Flux<String> stream(List<String> modelIds, Function<String, Flux<String>> call) {
        return stream(modelIds, null, call);
    }

    /**
     * 按模型链顺序执行流式调用，单模型无新 token 超过 {@code perModelTimeout} 视为失败并切换下一模型。
     *
     * @param modelIds        模型链（主模型 + 备用模型，已按优先级排序）
     * @param perModelTimeout 单模型超时（null/非正数表示不设置）
     * @param call            单模型调用工厂（入参为模型ID）
     * @return token 流
     */
    public Flux<String> stream(List<String> modelIds, Duration perModelTimeout,
                               Function<String, Flux<String>> call) {
        if (modelIds == null || modelIds.isEmpty()) {
            return Flux.error(new ModelChainExhaustedException());
        }
        return tryAt(modelIds, 0, perModelTimeout, call, new AtomicBoolean(false), new AtomicReference<>());
    }

    /**
     * 尝试链中指定下标的模型；失败或超时且未产生 token 时递归尝试下一个。
     *
     * @param ids             模型链
     * @param index           当前模型下标
     * @param perModelTimeout 单模型超时
     * @param call            单模型调用工厂
     * @param emitted         是否已产生 token（链级共享标记）
     * @param lastError       链内最后一次失败（供链耗尽时保留超时语义）
     * @return token 流
     */
    private Flux<String> tryAt(List<String> ids, int index, Duration perModelTimeout,
                               Function<String, Flux<String>> call, AtomicBoolean emitted,
                               AtomicReference<Throwable> lastError) {
        if (index >= ids.size()) {
            return Flux.error(new ModelChainExhaustedException(lastError.get()));
        }
        return Flux.defer(() -> {
                    Flux<String> candidate = call.apply(ids.get(index));
                    return perModelTimeout != null && !perModelTimeout.isZero() && !perModelTimeout.isNegative()
                            ? candidate.timeout(perModelTimeout)
                            : candidate;
                })
                .filter(token -> token != null && !token.isEmpty())
                .doOnNext(token -> emitted.set(true))
                .onErrorResume(e -> {
                    lastError.set(e);
                    return emitted.get()
                            ? Flux.error(e)
                            : tryAt(ids, index + 1, perModelTimeout, call, emitted, lastError);
                });
    }

    /**
     * 模型链耗尽异常（主模型及所有备用模型均在首 token 前失败/超时，或链为空）。
     * <p>
     * 独立类型便于上层在终止路径上区分「链耗尽」与其他流异常，做准确的失败计数；
     * cause 保留链内最后一次失败，便于识别“全部候选超时”。
     */
    public static class ModelChainExhaustedException extends IllegalStateException {

        public ModelChainExhaustedException() {
            super("无可用模型");
        }

        public ModelChainExhaustedException(Throwable cause) {
            super("无可用模型", cause);
        }
    }
}
