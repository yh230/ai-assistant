package com.aiassistant.config;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

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
}
