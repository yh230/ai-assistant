package com.aiassistant.config;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 模型故障转移执行器测试：首 token 前切换、部分输出后不切换、全部失败。
 *
 * @author yh
 * @date 2026-10-19
 */
class ModelStreamExecutorTest {

    @Test
    void fallsBackWhenNoTokenEmitted() {
        AtomicInteger calls = new AtomicInteger();
        List<String> out = new ModelStreamExecutor().stream(List.of("a", "b"), model -> {
            if (calls.getAndIncrement() == 0) {
                return Flux.error(new RuntimeException("boom"));
            }
            return Flux.just("ok");
        }).collectList().block();
        assertEquals(List.of("ok"), out);
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotFallbackAfterPartialOutput() {
        AtomicInteger calls = new AtomicInteger();
        List<String> out = new ModelStreamExecutor().stream(List.of("a", "b"), model -> {
            calls.incrementAndGet();
            return Flux.concat(Flux.just("partial"), Flux.error(new RuntimeException("boom")));
        })
                .onErrorResume(e -> Flux.just("中断"))
                .collectList().block();
        assertEquals(List.of("partial", "中断"), out);
        assertEquals(1, calls.get());
    }

    @Test
    void failsWhenAllModelsFail() {
        assertThrows(IllegalStateException.class, () -> new ModelStreamExecutor()
                .stream(List.of("a", "b"), model -> Flux.error(new RuntimeException(model + " down")))
                .collectList().block());
    }

    @Test
    void failsWhenChainIsNullOrEmpty() {
        ModelStreamExecutor executor = new ModelStreamExecutor();
        assertThrows(IllegalStateException.class, () -> executor
                .stream(null, model -> Flux.just("ok"))
                .collectList().block());
        assertThrows(IllegalStateException.class, () -> executor
                .stream(List.of(), model -> Flux.just("ok"))
                .collectList().block());
    }

    @Test
    void emptyTokensDoNotCountAsEmitted() {
        AtomicInteger calls = new AtomicInteger();
        List<String> out = new ModelStreamExecutor().stream(List.of("a", "b"), model -> {
            if (calls.getAndIncrement() == 0) {
                // 空串（工具调用等无文本片段）不算已输出，后续失败仍应切换下一模型
                return Flux.concat(Flux.just("", ""), Flux.error(new RuntimeException("boom")));
            }
            return Flux.just("ok");
        }).collectList().block();
        assertEquals(List.of("ok"), out);
        assertEquals(2, calls.get());
    }

    @Test
    void filtersEmptyTokensFromOutput() {
        List<String> out = new ModelStreamExecutor().stream(List.of("a"),
                        model -> Flux.just("", "hello", ""))
                .collectList().block();
        assertEquals(List.of("hello"), out);
    }

    @Test
    void fallsBackWhenCandidateTimesOutBeforeToken() {
        AtomicInteger calls = new AtomicInteger();
        List<String> out = new ModelStreamExecutor().stream(List.of("a", "b"), Duration.ofMillis(50), model -> {
            if (calls.getAndIncrement() == 0) {
                return Flux.never();
            }
            return Flux.just("ok");
        }).collectList().block();
        assertEquals(List.of("ok"), out);
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotFallbackWhenTimeoutAfterToken() {
        AtomicInteger calls = new AtomicInteger();
        List<String> out = new ModelStreamExecutor().stream(List.of("a", "b"), Duration.ofMillis(50), model -> {
                    calls.incrementAndGet();
                    return Flux.concat(Flux.just("partial"), Flux.never());
                })
                .onErrorResume(e -> Flux.just("中断"))
                .collectList().block();
        assertEquals(List.of("partial", "中断"), out);
        assertEquals(1, calls.get());
    }
}
