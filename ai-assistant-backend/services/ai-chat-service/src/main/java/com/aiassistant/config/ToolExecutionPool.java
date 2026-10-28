package com.aiassistant.config;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 工具执行线程池：按配置固定线程数，所有请求共享。
 * <p>
 * 工具调用是同步阻塞的（Feign/HTTP），用独立线程池执行并配合单次调用超时，
 * 避免慢工具占用模型流线程；线程数可配置，避免全局只有固定 8 个线程造成跨请求头阻塞。
 *
 * @author yh
 * @date 2026-10-27
 */
@Component
public class ToolExecutionPool {

    private final ExecutorService executor;

    /**
     * 构造工具执行线程池。
     *
     * @param props AI 配置（工具线程池大小）
     */
    public ToolExecutionPool(AiProperties props) {
        int threads = Math.max(1, props.getChat().getToolExecutorThreads());
        this.executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "ai-tool-guard");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 获取工具执行线程池。
     *
     * @return 线程池
     */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * 应用关闭时停止线程池。
     */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
