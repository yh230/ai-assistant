package com.aiassistant.config;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 模型故障转移执行器：按给定模型链顺序尝试，仅当尚未输出任何 token 时才切换到下一个模型。
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>首 token 前可重试</b>：模型调用失败且尚未产生 token 时，自动尝试链中的下一个模型；</li>
 *     <li><b>部分输出后不切换</b>：已向客户端推送 token 后再切换模型会造成内容拼接错乱，故直接透传异常；</li>
 *     <li><b>全部失败</b>：模型链耗尽后以 {@link IllegalStateException} 结束，由上层降级本地引擎。</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
public class ModelStreamExecutor {

    /**
     * 按模型链顺序执行流式调用，首 token 前失败自动故障转移。
     *
     * @param modelIds 模型链（主模型 + 备用模型，已按优先级排序）
     * @param call     单模型调用工厂（入参为模型ID）
     * @return token 流
     */
    public Flux<String> stream(List<String> modelIds, Function<String, Flux<String>> call) {
        if (modelIds == null || modelIds.isEmpty()) {
            return Flux.error(new IllegalStateException("无可用模型"));
        }
        return tryAt(modelIds, 0, call, new AtomicBoolean(false));
    }

    /**
     * 尝试链中指定下标的模型；失败且未产生 token 时递归尝试下一个。
     *
     * @param ids     模型链
     * @param index   当前模型下标
     * @param call    单模型调用工厂
     * @param emitted 是否已产生 token（链级共享标记）
     * @return token 流
     */
    private Flux<String> tryAt(List<String> ids, int index, Function<String, Flux<String>> call,
                               AtomicBoolean emitted) {
        if (index >= ids.size()) {
            return Flux.error(new IllegalStateException("无可用模型"));
        }
        return Flux.defer(() -> call.apply(ids.get(index)))
                .doOnNext(token -> emitted.set(true))
                .onErrorResume(e -> emitted.get()
                        ? Flux.error(e)
                        : tryAt(ids, index + 1, call, emitted));
    }
}
