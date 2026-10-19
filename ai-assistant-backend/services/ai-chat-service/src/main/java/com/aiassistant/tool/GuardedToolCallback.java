package com.aiassistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具回调安全包装：调用次数上限、单次调用超时与执行异常兜底，并记录调用结果指标
 * <p>
 * 模型生成的工具参数不可信，底层工具又可能因外部服务阻塞或抛异常。本包装保证：
 * <ul>
 *     <li><b>次数上限</b>：同一请求内所有工具共享一个 {@link AtomicInteger} 剩余次数，
 *         超出上限不再执行底层工具，直接返回结构化错误，交由模型基于已有信息作答。</li>
 *     <li><b>调用超时</b>：单次调用在独立线程执行，超时即取消并返回结构化错误，避免拖垮模型流。</li>
 *     <li><b>异常兜底</b>：底层工具抛出的任何异常（含堆栈）不外泄，统一转为 {@code {"error":"..."}} JSON。</li>
 *     <li><b>可观测</b>：每次调用记录 {@code ai.tool.calls{tool,result}}，result 取
 *         success/validation_error/limit/timeout/error；底层工具正常返回但结果是错误 JSON（如参数校验失败）
 *         时记为 {@code validation_error}，避免把校验失败混入成功。</li>
 * </ul>
 * 工具定义（{@link #getToolDefinition()}）原样透传，确保写工具识别与工具描述不丢失。
 *
 * @author yh
 * @date 2026-10-19
 */
@Slf4j
public class GuardedToolCallback implements ToolCallback {

    /** 指标名：工具调用计数 */
    public static final String METRIC_TOOL_CALLS = "ai.tool.calls";

    /** 结果标签：成功 */
    public static final String RESULT_SUCCESS = "success";

    /** 结果标签：参数校验失败（底层工具返回结构化错误 JSON） */
    public static final String RESULT_VALIDATION_ERROR = "validation_error";

    /** 结果标签：超出调用次数上限 */
    public static final String RESULT_LIMIT = "limit";

    /** 结果标签：调用超时 */
    public static final String RESULT_TIMEOUT = "timeout";

    /** 结果标签：执行异常 */
    public static final String RESULT_ERROR = "error";

    /** 结构化错误序列化器（仅用于错误响应，线程安全） */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    /** 工具执行线程池线程数：固定上限，防止不响应中断的底层调用无限累积线程 */
    private static final int TOOL_EXECUTOR_THREADS = 8;

    /**
     * 工具执行线程池（固定 8 个守护线程）
     * <p>
     * 超时只取消 Future 并中断执行线程；若底层 HTTP 客户端不响应中断，线程会继续占用至其自身超时。
     * 使用无界 cached pool 时这类线程会随调用次数无限增长，故改为固定大小守护线程池，
     * 把线程占用上界钉死在 {@value #TOOL_EXECUTOR_THREADS} 个。代价是并发工具调用超过该数量时
     * 后续任务在队列中排队并更早触发包装层超时（返回结构化超时错误），属于用少量吞吐换资源可预测性。
     */
    private static final ExecutorService TOOL_EXECUTOR = Executors.newFixedThreadPool(
            TOOL_EXECUTOR_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "ai-tool-guard");
                thread.setDaemon(true);
                return thread;
            });

    /** 被包装的原始工具回调 */
    private final ToolCallback delegate;

    /** 同一次请求内共享的剩余调用次数（每次调用先自减，小于 0 即拒绝执行） */
    private final AtomicInteger remainingCalls;

    /** 单次调用超时时间 */
    private final Duration timeout;

    /** 指标注册表 */
    private final MeterRegistry meterRegistry;

    /**
     * 以秒为单位构造包装（超时值非正时回退 1 秒，避免无限等待）
     *
     * @param delegate       原始工具回调
     * @param remainingCalls 同一请求内共享的剩余调用次数
     * @param timeoutSeconds 单次调用超时（秒）
     * @param meterRegistry  指标注册表
     */
    public GuardedToolCallback(ToolCallback delegate, AtomicInteger remainingCalls, int timeoutSeconds,
                               MeterRegistry meterRegistry) {
        this(delegate, remainingCalls,
                Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 1), meterRegistry);
    }

    /**
     * 以时长构造包装（便于按毫秒精度控制超时）
     *
     * @param delegate       原始工具回调
     * @param remainingCalls 同一请求内共享的剩余调用次数
     * @param timeout        单次调用超时
     * @param meterRegistry  指标注册表
     */
    public GuardedToolCallback(ToolCallback delegate, AtomicInteger remainingCalls, Duration timeout,
                               MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.remainingCalls = remainingCalls;
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(1) : timeout;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = resolveToolName();
        // 先扣减再判断：保证并发调用下不会超过配额
        if (remainingCalls.decrementAndGet() < 0) {
            record(toolName, RESULT_LIMIT);
            log.warn("AI 工具调用超出上限，已拒绝执行: tool={}", toolName);
            return errorJson("本次请求工具调用次数已达上限，请直接根据已获取的信息回答患者");
        }

        Future<String> future = TOOL_EXECUTOR.submit(() -> delegate.call(toolInput, toolContext));
        try {
            String result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            // 底层工具正常返回但结果是错误 JSON（参数校验失败）时单独打标，避免污染 success
            record(toolName, isErrorResult(result) ? RESULT_VALIDATION_ERROR : RESULT_SUCCESS);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            record(toolName, RESULT_TIMEOUT);
            log.warn("AI 工具调用超时（{} 毫秒）: tool={}", timeout.toMillis(), toolName);
            return errorJson("工具查询超时，请稍后重试或直接根据已有信息回答患者");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            record(toolName, RESULT_ERROR);
            log.warn("AI 工具调用被中断: tool={}", toolName);
            return errorJson("工具调用被中断，请稍后重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            record(toolName, RESULT_ERROR);
            // 仅记录异常摘要，堆栈留在服务端日志，不回传模型
            log.warn("AI 工具执行异常: tool={}, {}", toolName, cause.getMessage(), cause);
            return errorJson(resolveErrorMessage(cause));
        }
    }

    /**
     * 解析工具名称（定义缺失时回退 unknown，保证指标维度可用）
     *
     * @return 工具名称
     */
    private String resolveToolName() {
        ToolDefinition definition = delegate.getToolDefinition();
        return definition == null || definition.name() == null ? "unknown" : definition.name();
    }

    /**
     * 判断底层工具返回值是否为结构化错误（顶层 JSON 对象且含非空 error 字段）
     * <p>
     * 仅用于指标归类，解析失败或非 JSON 一律按正常结果处理，不影响回传模型的内容。
     *
     * @param result 底层工具返回的 JSON 字符串
     * @return 是错误对象时返回 true
     */
    private boolean isErrorResult(String result) {
        if (result == null) {
            return false;
        }
        String trimmed = result.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        try {
            JsonNode node = ERROR_MAPPER.readTree(trimmed);
            return node != null && node.isObject() && node.hasNonNull("error");
        } catch (Exception e) {
            log.debug("工具返回内容非合法 JSON，按正常结果处理: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 提取可回传模型的错误文案（仅取异常消息，空则使用通用文案，绝不回传堆栈）
     *
     * @param cause 底层异常
     * @return 脱敏后的错误文案
     */
    private String resolveErrorMessage(Throwable cause) {
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            return "工具执行失败，请稍后重试或直接根据已有信息回答患者";
        }
        return cause.getMessage();
    }

    /**
     * 构造结构化错误 JSON
     *
     * @param message 错误文案
     * @return {@code {"error":"..."}} JSON 字符串
     */
    private String errorJson(String message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("error", message);
        try {
            return ERROR_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            // 理论上不会发生（仅 String 值），兜底返回固定 JSON，保证模型始终收到合法结构
            log.warn("工具错误 JSON 序列化失败: {}", e.getMessage());
            return "{\"error\":\"工具执行失败，请稍后重试\"}";
        }
    }

    /**
     * 记录工具调用结果指标
     *
     * @param toolName 工具名称
     * @param result   结果标签
     */
    private void record(String toolName, String result) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(METRIC_TOOL_CALLS, "tool", toolName, "result", result).increment();
    }
}
