package com.aiassistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具回调安全包装单元测试（次数上限、调用超时、异常兜底、正常透传与指标）
 *
 * @author yh
 * @date 2026-10-19
 */
class GuardedToolCallbackTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void passesThroughResultAndRecordsSuccess() {
        ToolCallback delegate = new StubToolCallback("demo", input -> "ok:" + input);
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, new AtomicInteger(3),
                Duration.ofSeconds(1), meterRegistry, executor);

        String result = guarded.call("{\"a\":1}", new ToolContext(Map.<String, Object>of("patientId", 1L)));

        assertEquals("ok:{\"a\":1}", result);
        assertEquals("demo", guarded.getToolDefinition().name());
        assertEquals(1.0, counterCount("demo", GuardedToolCallback.RESULT_SUCCESS));
        assertEquals(0.0, counterCount("demo", GuardedToolCallback.RESULT_LIMIT));
    }

    @Test
    void preservesToolDefinition() {
        ToolCallback delegate = new StubToolCallback("bookAppointment", input -> "done");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, new AtomicInteger(3), 5, meterRegistry, executor);

        ToolDefinition definition = guarded.getToolDefinition();

        assertNotNull(definition);
        assertEquals("bookAppointment", definition.name());
        assertEquals("测试工具", definition.description());
    }

    @Test
    void rejectsCallsBeyondSharedLimitWithStructuredError() throws Exception {
        AtomicInteger remaining = new AtomicInteger(2);
        AtomicInteger executed = new AtomicInteger();
        ToolCallback delegate = new StubToolCallback("demo", input -> {
            executed.incrementAndGet();
            return "ok";
        });
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, remaining,
                Duration.ofSeconds(1), meterRegistry, executor);

        assertEquals("ok", guarded.call("{}", null));
        assertEquals("ok", guarded.call("{}", null));
        String limited = guarded.call("{}", null);

        JsonNode error = OBJECT_MAPPER.readTree(limited);
        assertTrue(error.hasNonNull("error"));
        assertTrue(error.get("error").asText().contains("上限"));
        assertEquals(2, executed.get(), "超出上限后不得再执行底层工具");
        assertEquals(1.0, counterCount("demo", GuardedToolCallback.RESULT_LIMIT));
    }

    @Test
    void returnsStructuredErrorWhenCallTimesOut() throws Exception {
        ToolCallback delegate = new StubToolCallback("demo", input -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, new AtomicInteger(3),
                Duration.ofMillis(200), meterRegistry, executor);

        String result = guarded.call("{}", null);

        JsonNode error = OBJECT_MAPPER.readTree(result);
        assertTrue(error.hasNonNull("error"));
        assertTrue(error.get("error").asText().contains("超时"));
        assertEquals(1.0, counterCount("demo", GuardedToolCallback.RESULT_TIMEOUT));
    }

    @Test
    void tagsStructuredErrorResultAsValidationError() throws Exception {
        ToolCallback delegate = new StubToolCallback("demo",
                input -> "{\"error\":\"科室ID不合法，请先查询科室列表获取正确ID\"}");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, new AtomicInteger(3),
                Duration.ofSeconds(1), meterRegistry, executor);

        String result = guarded.call("{}", null);

        JsonNode payload = OBJECT_MAPPER.readTree(result);
        assertTrue(payload.hasNonNull("error"));
        assertEquals(1.0, counterCount("demo", GuardedToolCallback.RESULT_VALIDATION_ERROR));
        assertEquals(0.0, counterCount("demo", GuardedToolCallback.RESULT_SUCCESS));
    }

    @Test
    void convertsDelegateExceptionToStructuredError() throws Exception {
        ToolCallback delegate = new StubToolCallback("demo", input -> {
            throw new IllegalStateException("号源查询服务暂时不可用，请稍后重试");
        });
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, new AtomicInteger(3),
                Duration.ofSeconds(1), meterRegistry, executor);

        String result = guarded.call("{}", null);

        JsonNode error = OBJECT_MAPPER.readTree(result);
        assertEquals("号源查询服务暂时不可用，请稍后重试", error.get("error").asText());
        assertEquals(1.0, counterCount("demo", GuardedToolCallback.RESULT_ERROR));
    }

    @Test
    void fallsBackToGenericMessageWhenExceptionHasNoMessage() throws Exception {
        ToolCallback delegate = new StubToolCallback("demo", input -> {
            throw new IllegalStateException();
        });
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, new AtomicInteger(3),
                Duration.ofSeconds(1), meterRegistry, executor);

        JsonNode error = OBJECT_MAPPER.readTree(guarded.call("{}", null));

        assertTrue(error.get("error").asText().contains("工具执行失败"));
    }

    /**
     * 查询指定 result 标签的计数（无样本时为 0）
     *
     * @param tool   工具名称
     * @param result 结果标签
     * @return 计数
     */
    private double counterCount(String tool, String result) {
        Counter counter = meterRegistry.find(GuardedToolCallback.METRIC_TOOL_CALLS)
                .tag("tool", tool).tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 测试用工具回调（固定定义 + 自定义执行逻辑）
     */
    private static final class StubToolCallback implements ToolCallback {

        private final ToolDefinition definition;
        private final java.util.function.Function<String, String> handler;

        private StubToolCallback(String name, java.util.function.Function<String, String> handler) {
            this.definition = DefaultToolDefinition.builder()
                    .name(name)
                    .description("测试工具")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
            this.handler = handler;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return handler.apply(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return handler.apply(toolInput);
        }
    }
}
