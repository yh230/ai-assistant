package com.aiassistant.service.impl;

import com.aiassistant.config.AiProperties;
import com.aiassistant.config.AssistantProperties;
import com.aiassistant.config.ChatClientFactory;
import com.aiassistant.config.ChatModelRegistry;
import com.aiassistant.dto.ChatResponse;
import com.aiassistant.dto.PatientChatRequest;
import com.aiassistant.dto.SessionDTO;
import com.aiassistant.entity.ChatMessage;
import com.aiassistant.entity.ChatSession;
import com.aiassistant.entity.PendingAction;
import com.aiassistant.rag.KnowledgeReferencesBuilder;
import com.aiassistant.rag.KnowledgeVectorStore;
import com.aiassistant.repository.ChatMessageRepository;
import com.aiassistant.repository.ChatSessionRepository;
import com.aiassistant.safety.OutputSafetyFilter;
import com.aiassistant.service.ActionExecutor;
import com.aiassistant.service.PatientChatService;
import com.aiassistant.service.PendingActionService;
import com.aiassistant.tool.PatientTools;
import com.common.auth.UserSnapshot;
import com.common.constant.ChatConstants;
import com.common.constant.SecurityConstants;
import com.common.exception.BusinessException;
import com.common.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 患者聊天服务实现 - 患者会话 SSE 主链路（闲聊/导诊/知识库三分支）
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>Flux 流式响应</b>：阻塞型 DB 操作与 AI 流经 {@code Schedulers.boundedElastic} 卸载，
 *         由 Spring MVC 自动适配为 HTTP SSE 流。</li>
 *     <li><b>会话隔离</b>：会话按 (userId, identity=patient) 绑定，读写一律限定当前登录患者，口径与员工侧一致。</li>
 *     <li><b>会话类型</b>：CHAT=闲聊 / TRIAGE=导诊（装配患者工具）/ RAG=知识库（知识库人设提示词 + 本院检索片段拼接），
 *         系统提示词取自 {@link AiProperties}。</li>
 *     <li><b>免责与降级</b>：每次流式回答（AI 或本地降级）末尾强制追加免责声明；模型不可用或流异常时降级本地回复引擎，对话不中断。</li>
 *     <li><b>输入输出风控</b>：命中敏感词规则的输入直接拦截；输出侧仅扫描生成正文，命中即丢弃生成内容并持久化标准文案。</li>
 *     <li><b>SSE 加固</b>：AI 生成 120 秒无新 token 按超时结束（error 事件输出固定文案）；客户端断开时记录日志并按已产生的部分内容（附免责）落库。</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientChatServiceImpl implements PatientChatService {

    // ==================== 常量 ====================

    /** 闲聊会话类型 */
    private static final String TYPE_CHAT = "CHAT";

    /** 导诊会话类型 */
    private static final String TYPE_TRIAGE = "TRIAGE";

    /** 知识库会话类型 */
    private static final String TYPE_RAG = "RAG";

    /** 上下文窗口（最近 N 条消息作为完整上下文） */
    private static final int CONTEXT_WINDOW = 15;

    /** 写工具名称（确认续答链路不装配，防止确认后再次签发待确认操作） */
    private static final List<String> WRITE_TOOL_NAMES = List.of("bookAppointment", "cancelAppointment");

    // ==================== 依赖 ====================

    private final AiProperties aiProperties;
    private final AssistantProperties assistantProperties;
    private final ChatModelRegistry chatModelRegistry;
    private final ChatClientFactory chatClientFactory;
    private final OutputSafetyFilter safetyFilter;
    private final PatientTools patientTools;
    private final PendingActionService pendingActionService;
    private final ActionExecutor actionExecutor;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final PlatformTransactionManager txManager;
    private final ObjectMapper objectMapper;
    private final KnowledgeVectorStore knowledgeVectorStore;
    private final KnowledgeReferencesBuilder knowledgeReferencesBuilder;
    private final MeterRegistry meterRegistry;

    /** 当前活跃患者 SSE 流数（Gauge 底层数据源） */
    private final AtomicInteger patientSseActive = new AtomicInteger();

    /**
     * 注册患者 SSE 活跃流 Gauge（容器就绪后执行一次）
     */
    @PostConstruct
    void registerSseActiveGauge() {
        meterRegistry.gauge("ai.patient.sse.active", patientSseActive);
    }

    // ==================== 流式对话（核心链路） ====================

    @Override
    public Flux<ServerSentEvent<String>> stream(PatientChatRequest request) {
        Assert.notNull(request, "请求参数不能为空");
        Assert.hasText(request.getMessage(), "消息内容不能为空");

        // 登录快照在请求线程捕获后透传：SecurityContext 为 ThreadLocal，弹性线程池内读不到
        UserSnapshot snapshot = currentPatient();

        return Mono.fromCallable(() -> prepare(request, snapshot))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::buildReplyFlux)
                .onErrorResume(this::buildErrorFlux);
    }

    /**
     * 准备上下文：归属会话 + 落库用户消息 + 组装历史与系统提示词（阻塞型 DB 操作，运行在弹性线程池）
     *
     * @param request  患者聊天请求
     * @param user     当前患者快照（请求线程捕获）
     * @return 患者聊天上下文
     */
    private PatientChatContext prepare(PatientChatRequest request, UserSnapshot user) {
        ChatSession session = ensureSession(request, user);
        saveMessage(session.getSessionId(), "user", request.getMessage());

        // 急症红线优先：命中即短路（不调模型），以正常消息返回紧急就医指引
        if (safetyFilter.emergencyHit(request.getMessage())) {
            String emergencyMessage = safetyFilter.emergencyMessage();
            saveMessage(session.getSessionId(), "assistant", emergencyMessage);
            meterRegistry.counter("ai.emergency.total").increment();
            log.info("输入侧命中急症红线，短路返回就医指引 sessionId={}", session.getSessionId());
            return new PatientChatContext(session, user, List.of(), "", request.getMessage(), "", emergencyMessage);
        }

        // 输入侧风控：命中敏感词不调用大模型，落库标准文案后以 error 事件输出
        if (safetyFilter.blockedInput(request.getMessage())) {
            String blockedMessage = safetyFilter.blockedMessage();
            saveMessage(session.getSessionId(), "assistant", blockedMessage);
            log.info("输入侧命中风控敏感词，拦截本次请求 sessionId={}", session.getSessionId());
            throw new BusinessException(blockedMessage);
        }

        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getSessionId());
        List<Message> contextMessages = history.stream()
                .skip(Math.max(0, history.size() - CONTEXT_WINDOW))
                .map(m -> (Message) ("user".equals(m.getRole())
                        ? new UserMessage(m.getContent()) : new AssistantMessage(m.getContent())))
                .toList();
        String systemPrompt = resolvePrompt(session.getSessionType());
        String referenceJson = "";
        if (TYPE_RAG.equals(session.getSessionType())) {
            List<Document> hits = knowledgeVectorStore.search(user.getHospitalId(), request.getMessage(),
                    aiProperties.getRag().getTopK(), aiProperties.getRag().getSimilarityThreshold());
            systemPrompt = systemPrompt + "\n\n" + buildKnowledgeContext(hits);
            referenceJson = knowledgeReferencesBuilder.build(hits, aiProperties.getRag().getMaxReferences());
        }

        log.info("患者流式对话 sessionId={}, sessionType={}, 历史消息数={}",
                session.getSessionId(), session.getSessionType(), history.size());
        return new PatientChatContext(session, user, contextMessages, systemPrompt, request.getMessage(), referenceJson, null);
    }

    /**
     * 构建完整回复流：会话事件 + 急症短路分支或内容流（AI 或本地降级，末尾追加免责 token）
     * + 输出侧风控兜底 + references 事件 + 写操作确认事件 + 结束事件
     *
     * @param ctx 患者聊天上下文
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> buildReplyFlux(PatientChatContext ctx) {
        String sessionId = ctx.session().getSessionId();
        ServerSentEvent<String> sessionEvent = ServerSentEvent.builder(
                        toJson(Map.of("sessionId", sessionId, "title", ctx.session().getTitle())))
                .event(ChatConstants.EVENT_SESSION)
                .build();

        // 急症红线短路：单条消息 + 结束（落库已在 prepare 完成，不调模型、不追加免责、不装配工具、不检查待确认）
        patientSseActive.incrementAndGet();
        if (ctx.emergencyReply() != null) {
            ServerSentEvent<String> emergencyEvent = ServerSentEvent.builder(ctx.emergencyReply())
                    .event(ChatConstants.EVENT_TOKEN)
                    .build();
            ServerSentEvent<String> emergencyDone = ServerSentEvent.builder(toJson(Map.of("localFallback", false)))
                    .event(ChatConstants.EVENT_DONE)
                    .build();
            return Flux.concat(Mono.just(sessionEvent), Mono.just(emergencyEvent), Mono.just(emergencyDone))
                    .doFinally(signal -> patientSseActive.decrementAndGet());
        }

        boolean aiEnabled = chatModelRegistry.isActive(assistantProperties.resolveModelId(null));
        AtomicBoolean fallback = new AtomicBoolean(!aiEnabled);
        AtomicBoolean persisted = new AtomicBoolean(false);
        AtomicBoolean blocked = new AtomicBoolean(false);
        StringBuilder full = new StringBuilder();
        Flux<ServerSentEvent<String>> contentFlux = aiEnabled
                ? buildAiContentFlux(ctx, full, fallback, resolvePatientToolCallbacks(ctx, true))
                : Flux.concat(buildLocalChunkFlux(ctx, full), disclaimerTokenFlux());

        Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() ->
                ServerSentEvent.builder(toJson(Map.of("localFallback", fallback.get())))
                        .event(ChatConstants.EVENT_DONE)
                        .build());

        // 引用来源：仅在模型作答、未降级、未被输出风控拦截且有命中时下发（降级/拦截时展示来源会误导）
        Mono<ServerSentEvent<String>> referencesEvent = Mono.defer(() ->
                (blocked.get() || fallback.get() || ctx.referenceJson().isEmpty())
                        ? Mono.empty()
                        : Mono.defer(() -> {
                            meterRegistry.counter("ai.rag.references.total").increment();
                            return Mono.just(ServerSentEvent.builder(ctx.referenceJson())
                                    .event(ChatConstants.EVENT_REFERENCES)
                                    .build());
                        }));

        return Flux.concat(Mono.just(sessionEvent), contentFlux,
                outputSafetyGuard(ctx, sessionId, full, persisted, fallback, blocked),
                referencesEvent,
                buildConfirmRequiredEvent(sessionId), doneEvent)
                .doOnCancel(() -> handleClientCancel(sessionId, full, persisted))
                .doFinally(signal -> patientSseActive.decrementAndGet());
    }

    /**
     * 构建写操作待确认事件：回复完成后查询会话内最新待确认操作，命中则推送确认事件
     *
     * @param sessionId 会话ID
     * @return 确认事件流；无待确认操作时为空流
     */
    private Mono<ServerSentEvent<String>> buildConfirmRequiredEvent(String sessionId) {
        return Mono.fromCallable(() -> pendingActionService.findLatestPending(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(pending -> pending
                        .map(action -> Mono.just(ServerSentEvent.builder(toJson(Map.of(
                                        "actionId", action.getActionId(),
                                        "toolName", action.getToolName(),
                                        "summary", action.getSummaryText())))
                                .event(ChatConstants.EVENT_CONFIRM_REQUIRED)
                                .build()))
                        .orElse(Mono.empty()));
    }

    /**
     * 装配患者工具回调节点：仅导诊会话装配；确认续答链路（includeWriteTools=false）排除写工具，
     * 避免确认后续答再次签发待确认操作
     *
     * @param ctx               患者聊天上下文
     * @param includeWriteTools 是否包含写工具（普通导诊链路为 true，确认续答为 false）
     * @return 工具回调列表
     */
    private List<ToolCallback> resolvePatientToolCallbacks(PatientChatContext ctx, boolean includeWriteTools) {
        if (!TYPE_TRIAGE.equals(ctx.session().getSessionType())) {
            return List.of();
        }
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(patientTools).build().getToolCallbacks())
                .filter(callback -> includeWriteTools
                        || !WRITE_TOOL_NAMES.contains(callback.getToolDefinition().name()))
                .toList();
    }

    /**
     * 构建 AI 模型流式内容：按入参装配工具 + 流尾免责追加；120 秒无新 token 按超时结束
     *
     * @param ctx      患者聊天上下文
     * @param full     回复内容累积器（供输出侧风控兜底与持久化使用）
     * @param fallback 降级标记（AI 流异常置为 true，供结束事件上报）
     * @param tools    本次请求装配的工具回调列表
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> buildAiContentFlux(PatientChatContext ctx, StringBuilder full,
                                                             AtomicBoolean fallback, List<ToolCallback> tools) {
        ChatClient client = chatClientFactory.getChatClientWithTools(
                assistantProperties.resolveModelId(null), tools);

        Flux<ServerSentEvent<String>> tokens = client.prompt()
                .system(ctx.systemPrompt())
                .messages(ctx.messages())
                .toolContext(Map.of("patientId", ctx.user().getUserId(),
                        "hospitalId", ctx.user().getHospitalId(),
                        "sessionId", ctx.session().getSessionId()))
                .stream()
                .content()
                .publishOn(Schedulers.boundedElastic())
                .map(token -> {
                    full.append(token);
                    return ServerSentEvent.builder(token).event(ChatConstants.EVENT_TOKEN).build();
                })
                .timeout(Duration.ofSeconds(120))
                .onErrorResume(ex -> {
                    if (ex instanceof TimeoutException) {
                        // 生成超时（120 秒无新 token）：按异常处理，固定文案由顶层转为 error 事件
                        log.warn("患者侧 AI 生成超时（120 秒无新 token）: sessionId={}", ctx.session().getSessionId());
                        return Flux.error(new BusinessException("生成超时，请稍后重试"));
                    }
                    log.warn("患者侧 AI 流式异常，降级本地引擎: {}", ex.getMessage());
                    fallback.set(true);
                    return buildLocalChunkFlux(ctx, full);
                });

        // 免责强制追加：token 流结束后补一个 token 事件（AI 与本地降级分支同款）
        return Flux.concat(tokens, disclaimerTokenFlux());
    }

    /**
     * 构建本地降级 token 流（按字符切片模拟打字机效果，内容追加进累积器供调用方落库）
     *
     * @param ctx  患者聊天上下文
     * @param full 完整回复内容累积器
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> buildLocalChunkFlux(PatientChatContext ctx, StringBuilder full) {
        String localReply = generateLocalResponse(ctx.userMessage());
        List<String> chunks = splitIntoChunks(localReply, ChatConstants.LOCAL_CHUNK_SIZE);
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(ChatConstants.LOCAL_STREAM_INTERVAL_MS))
                .map(chunk -> {
                    full.append(chunk);
                    return ServerSentEvent.builder(chunk).event(ChatConstants.EVENT_TOKEN).build();
                });
    }

    /**
     * 构建免责声明 token 事件（流尾强制追加，与正文 token 同格式）
     *
     * @return 单事件流
     */
    private Flux<ServerSentEvent<String>> disclaimerTokenFlux() {
        return Flux.just(ServerSentEvent.builder("\n\n" + aiProperties.getDisclaimer())
                .event(ChatConstants.EVENT_TOKEN)
                .build());
    }

    /**
     * 输出侧风控兜底：内容流结束后对累计正文做敏感词校验，命中则丢弃生成内容改持久化标准文案并追加 error 事件
     * <p>
     * 判定仅扫描模型/本地生成的正文（不含免责文案与固定前置话术，防关键词与免责重叠致全量误拦）；
     * token 已流出的场景前端以 error 文案为准；未命中则按「正文 + 免责」持久化（AI 与本地降级分支同款），
     * 模型作答未降级时引用 JSON 随消息一并持久化
     *
     * @param ctx       患者聊天上下文
     * @param sessionId 会话ID
     * @param full      回复正文累积器
     * @param persisted 已持久化标记（客户端断开兜底落库去重用）
     * @param fallback  降级标记（降级回复不持久化引用）
     * @param blocked   输出侧拦截标记（供 references 事件下发判定）
     * @return 命中时输出 error 事件流，未命中则为空流
     */
    private Mono<ServerSentEvent<String>> outputSafetyGuard(PatientChatContext ctx, String sessionId,
                                                            StringBuilder full, AtomicBoolean persisted,
                                                            AtomicBoolean fallback, AtomicBoolean blocked) {
        return Mono.defer(() -> {
            String body = full.toString();
            if (safetyFilter.blockedInput(body)) {
                blocked.set(true);
                persisted.set(true);
                persistReplyAsync(sessionId, safetyFilter.blockedMessage(), null);
                log.warn("输出侧命中风控敏感词，已拦截并改持久化标准文案 sessionId={}", sessionId);
                return Mono.just(ServerSentEvent.builder(toJson(Map.of("message", safetyFilter.blockedMessage())))
                        .event(ChatConstants.EVENT_ERROR)
                        .build());
            }
            persisted.set(true);
            String referenceJson = (!fallback.get() && !ctx.referenceJson().isEmpty()) ? ctx.referenceJson() : null;
            persistReplyAsync(sessionId, body + "\n\n" + aiProperties.getDisclaimer(), referenceJson);
            return Mono.empty();
        });
    }

    /**
     * SSE 客户端断开处理：记录日志；本次回复尚未落库且已产生部分内容时，按部分内容（附免责）落库
     *
     * @param sessionId 会话ID
     * @param full      回复正文累积器
     * @param persisted 已持久化标记
     */
    private void handleClientCancel(String sessionId, StringBuilder full, AtomicBoolean persisted) {
        log.info("SSE 客户端断开: sessionId={}", sessionId);
        if (persisted.compareAndSet(false, true) && full.length() > 0) {
            persistReplyAsync(sessionId, full + "\n\n" + aiProperties.getDisclaimer(), null);
        }
    }

    /**
     * 顶层异常处理：转换为 SSE error 事件
     *
     * @param ex 异常
     * @return 单事件流
     */
    private Flux<ServerSentEvent<String>> buildErrorFlux(Throwable ex) {
        log.error("患者流式聊天处理失败", ex);
        String msg = ex instanceof BusinessException ? ex.getMessage() : "服务暂时不可用，请稍后重试";
        return Flux.just(ServerSentEvent.builder(toJson(Map.of("message", msg)))
                .event(ChatConstants.EVENT_ERROR)
                .build());
    }

    // ==================== 写操作确认续答 ====================

    @Override
    public Flux<ServerSentEvent<String>> confirmAction(String actionId) {
        Assert.hasText(actionId, "确认ID不能为空");
        // 登录快照在请求线程捕获后透传：SecurityContext 为 ThreadLocal，弹性线程池内读不到
        UserSnapshot snapshot = currentPatient();

        return Mono.fromCallable(() -> prepareConfirm(actionId, snapshot))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::buildConfirmReplyFlux)
                .onErrorResume(this::buildErrorFlux);
    }

    @Override
    public void cancelAction(String actionId) {
        Assert.hasText(actionId, "确认ID不能为空");
        UserSnapshot user = currentPatient();
        pendingActionService.cancel(actionId, user.getUserId(), SecurityConstants.IDENTITY_PATIENT);
    }

    /**
     * 准备确认续答上下文：状态机确认 → 执行写工具 → 落库确认消息 → 组装历史与结果转述指令（阻塞型操作，运行在弹性线程池）
     * <p>
     * 不加外层事务：确认状态机自带事务边界（过期置位 EXPIRED 依赖其 noRollbackFor 语义），
     * 外层默认事务会令置位随业务异常回滚
     *
     * @param actionId 确认ID
     * @param user     当前患者快照（请求线程捕获）
     * @return 患者聊天上下文
     */
    private PatientChatContext prepareConfirm(String actionId, UserSnapshot user) {
        PendingAction action = pendingActionService.confirm(actionId, user.getUserId(),
                SecurityConstants.IDENTITY_PATIENT);
        String resultText = actionExecutor.execute(action, user);
        ChatSession session = requireOwnedSession(action.getSessionId(), user);

        String confirmMessage = "已确认执行：" + action.getSummaryText();
        saveMessage(session.getSessionId(), "user", confirmMessage);

        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getSessionId());
        List<Message> contextMessages = new ArrayList<>(history.stream()
                .skip(Math.max(0, history.size() - CONTEXT_WINDOW))
                .map(m -> (Message) ("user".equals(m.getRole())
                        ? new UserMessage(m.getContent()) : new AssistantMessage(m.getContent())))
                .toList());
        contextMessages.add(new UserMessage("[系统] 用户已确认执行「" + action.getSummaryText()
                + "」。操作结果：" + resultText + "。请用通俗友好的语言把结果告知用户，不要重复请求确认。"));

        log.info("患者确认写操作 actionId={}, tool={}, 会话={}, 结果={}",
                actionId, action.getToolName(), session.getSessionId(), resultText);
        return new PatientChatContext(session, user, contextMessages,
                resolvePrompt(session.getSessionType()), confirmMessage, "", null);
    }

    /**
     * 构建确认续答流：AI 内容（仅只读工具，不再签发待确认操作）+ 输出侧风控兜底 + 结束事件
     *
     * @param ctx 患者聊天上下文
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> buildConfirmReplyFlux(PatientChatContext ctx) {
        patientSseActive.incrementAndGet();
        String sessionId = ctx.session().getSessionId();
        boolean aiEnabled = chatModelRegistry.isActive(assistantProperties.resolveModelId(null));
        AtomicBoolean fallback = new AtomicBoolean(!aiEnabled);
        AtomicBoolean persisted = new AtomicBoolean(false);
        AtomicBoolean blocked = new AtomicBoolean(false);
        StringBuilder full = new StringBuilder();
        Flux<ServerSentEvent<String>> contentFlux = aiEnabled
                ? buildAiContentFlux(ctx, full, fallback, resolvePatientToolCallbacks(ctx, false))
                : Flux.concat(buildLocalChunkFlux(ctx, full), disclaimerTokenFlux());

        Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() ->
                ServerSentEvent.builder(toJson(Map.of("localFallback", fallback.get())))
                        .event(ChatConstants.EVENT_DONE)
                        .build());

        return Flux.concat(contentFlux, outputSafetyGuard(ctx, sessionId, full, persisted, fallback, blocked), doneEvent)
                .doOnCancel(() -> handleClientCancel(sessionId, full, persisted))
                .doFinally(signal -> patientSseActive.decrementAndGet());
    }

    // ==================== 历史与列表 ====================

    @Override
    @Transactional(readOnly = true)
    public List<SessionDTO> listSessions() {
        UserSnapshot user = currentPatient();
        return sessionRepository.findAllByUserIdAndIdentityOrderByUpdatedAtDesc(
                        user.getUserId(), SecurityConstants.IDENTITY_PATIENT).stream()
                .map(s -> SessionDTO.builder()
                        .sessionId(s.getSessionId())
                        .title(s.getTitle())
                        .sessionType(s.getSessionType())
                        .updatedAt(s.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ChatResponse getHistory(String sessionId) {
        UserSnapshot user = currentPatient();
        requireOwnedSession(sessionId, user);
        List<ChatResponse.ChatMessageDTO> messages = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(m -> ChatResponse.ChatMessageDTO.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .createdAt(m.getCreatedAt())
                        .referenceJson(m.getReferenceJson())
                        .build())
                .collect(Collectors.toList());
        return ChatResponse.builder()
                .sessionId(sessionId)
                .messages(messages)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Override
    @Transactional
    public ChatResponse createSession(String type) {
        UserSnapshot user = currentPatient();
        ChatSession session = createSessionEntity("新对话", user, resolveSessionType(type));
        return ChatResponse.builder()
                .sessionId(session.getSessionId())
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        UserSnapshot user = currentPatient();
        ChatSession session = requireOwnedSession(sessionId, user);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
    }

    // ==================== 归属与类型解析 ====================

    /**
     * 确保会话存在且归属当前患者：携带 sessionId 时校验归属，否则按类型新建
     *
     * @param request 患者聊天请求
     * @param user    当前患者快照
     * @return 归属当前患者的会话实体
     */
    private ChatSession ensureSession(PatientChatRequest request, UserSnapshot user) {
        String sessionId = request.getSessionId();
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return requireOwnedSession(sessionId, user);
        }
        return createSessionEntity(resolveTitle(request.getMessage()), user,
                resolveSessionType(request.getSessionType()));
    }

    /**
     * 获取当前登录患者快照（接口位于认证过滤器之后，快照缺失按未登录处理）
     *
     * @return 当前登录患者快照
     */
    private UserSnapshot currentPatient() {
        UserSnapshot snapshot = SecurityUtils.currentSnapshot();
        if (snapshot == null) {
            throw new BusinessException("未登录");
        }
        if (!SecurityConstants.IDENTITY_PATIENT.equals(snapshot.getIdentity())) {
            throw new BusinessException("无权访问");
        }
        return snapshot;
    }

    /**
     * 校验会话归属并返回实体（仅限当前患者的会话）
     *
     * @param sessionId 会话ID
     * @param user      当前患者快照
     * @return 归属当前患者的会话实体
     */
    private ChatSession requireOwnedSession(String sessionId, UserSnapshot user) {
        return sessionRepository.findBySessionIdAndUserIdAndIdentity(sessionId, user.getUserId(),
                        SecurityConstants.IDENTITY_PATIENT)
                .orElseThrow(() -> new BusinessException("会话不存在或无权访问"));
    }

    /**
     * 解析新建会话类型：仅允许 CHAT/TRIAGE/RAG，其余（含为空）按缺省 CHAT 处理
     * <p>
     * 已存在会话不回传类型解析，直接沿用库中 session_type（由 ensureSession 返回的实体承载）
     *
     * @param requestedType 请求指定的会话类型
     * @return 合法的会话类型
     */
    private String resolveSessionType(String requestedType) {
        String normalized = requestedType == null ? "" : requestedType.trim().toUpperCase();
        return switch (normalized) {
            case TYPE_TRIAGE, TYPE_RAG, TYPE_CHAT -> normalized;
            default -> TYPE_CHAT;
        };
    }

    /**
     * 按会话类型解析系统提示词（导诊/知识库/闲聊三分支）
     *
     * @param sessionType 会话类型
     * @return 对应人设的系统提示词
     */
    private String resolvePrompt(String sessionType) {
        if (TYPE_TRIAGE.equals(sessionType)) {
            return aiProperties.getPrompt().getTriage();
        }
        if (TYPE_RAG.equals(sessionType)) {
            return aiProperties.getPrompt().getRag();
        }
        return aiProperties.getPrompt().getChat();
    }

    /**
     * 组装知识库片段上下文（[1][2] 编号拼接；无命中时明确告知模型如实说明，不得编造）
     *
     * @param hits 本院知识库检索命中片段
     * @return 拼接到知识库人设提示词之后的片段上下文
     */
    private String buildKnowledgeContext(List<Document> hits) {
        if (hits.isEmpty()) {
            return "【知识库片段】未检索到与问题相关的内容，请如实告知患者知识库未收录，不要编造。";
        }
        StringBuilder sb = new StringBuilder("【知识库片段】\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(hits.get(i).getText()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成会话标题（取消息前 30 字，超长截断加省略号）
     *
     * @param message 用户消息
     * @return 会话标题
     */
    private String resolveTitle(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "新对话";
        }
        return message.length() > 30 ? message.substring(0, 30) + "..." : message;
    }

    // ==================== DB 辅助 ====================

    /**
     * 创建归属当前患者的会话实体
     *
     * @param title       会话标题
     * @param user        当前患者快照
     * @param sessionType 会话类型
     * @return 已保存的会话实体
     */
    private ChatSession createSessionEntity(String title, UserSnapshot user, String sessionType) {
        return sessionRepository.save(ChatSession.builder()
                .sessionId(UUID.randomUUID().toString().replace("-", ""))
                .title(title)
                .userId(user.getUserId())
                .identity(SecurityConstants.IDENTITY_PATIENT)
                .sessionType(sessionType)
                .hospitalId(user.getHospitalId())
                .build());
    }

    /**
     * 落库消息（事务内执行，运行于弹性线程池需独立事务模板）
     *
     * @param sessionId 会话ID
     * @param role      消息角色（user/assistant）
     * @param content   消息内容
     */
    private void saveMessage(String sessionId, String role, String content) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                messageRepository.save(ChatMessage.builder()
                        .sessionId(sessionId).role(role).content(content).build()));
    }

    /**
     * 异步落库 AI 回复（卸载到弹性线程池，避免阻塞响应流）
     *
     * @param sessionId     会话ID
     * @param content       回复内容
     * @param referenceJson 引用来源 JSON（无来源传 null）
     */
    private void persistReplyAsync(String sessionId, String content, String referenceJson) {
        if (content == null || content.isBlank()) {
            return;
        }
        Schedulers.boundedElastic().schedule(() -> saveAssistantMessage(sessionId, content, referenceJson));
    }

    /**
     * 落库 AI 回复并刷新会话更新时间（事务内）
     *
     * @param sessionId     会话ID
     * @param content       回复内容
     * @param referenceJson 引用来源 JSON（无来源传 null）
     */
    private void saveAssistantMessage(String sessionId, String content, String referenceJson) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            messageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId).role("assistant").content(content)
                    .referenceJson(referenceJson).build());
            sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
                s.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(s);
            });
        });
    }

    // ==================== 本地回复引擎（降级方案） ====================

    /**
     * 生成本地降级回复（无可用模型或 AI 流异常时兜底，简版话术）
     *
     * @param userMessage 用户消息
     * @return 本地生成的回复文本
     */
    private String generateLocalResponse(String userMessage) {
        String msg = userMessage == null ? "" : userMessage.trim();
        if (msg.contains("你好") || msg.contains("您好") || msg.contains("hi") || msg.contains("hello")) {
            return "您好，我是医院 AI 助手。当前智能问答服务暂时不可用（本地模式）："
                    + "如需导诊或挂号，请描述您的症状或想就诊的科室，或前往医院线下分诊台咨询。";
        }
        if (msg.contains("谢谢") || msg.contains("感谢")) {
            return "不客气，祝您早日康复。如有其他需要，请随时告诉我。";
        }
        if (msg.contains("再见") || msg.contains("拜拜")) {
            return "再见，祝您身体健康。后续如需导诊或挂号帮助，欢迎随时回来咨询。";
        }
        return "您说的是：「" + msg + "」。当前智能问答服务暂时不可用（本地模式），"
                + "导诊相关信息建议稍后重试或前往医院线下分诊台咨询。";
    }

    /**
     * 将文本按固定长度切片
     *
     * @param text      原始文本
     * @param chunkSize 每片字符数
     * @return 切片列表；文本为空时返回空列表
     */
    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            chunks.add(text.substring(i, end));
        }
        return chunks;
    }

    /**
     * 将对象序列化为 JSON 字符串（失败返回空对象）
     *
     * @param obj 待序列化对象
     * @return JSON 字符串
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    // ==================== 内部上下文记录 ====================

    /**
     * 患者聊天上下文（流式对话单次请求的装配结果）
     *
     * @param session       归属会话
     * @param user          当前患者快照
     * @param messages      组装好的 Spring AI 上下文消息
     * @param systemPrompt  最终系统提示词
     * @param userMessage   当前用户消息
     * @param referenceJson 引用来源 JSON（RAG 命中时非空；无来源为空串）
     * @param emergencyReply 急症红线短路回复（命中时非 null，服务层据此走短路链路）
     */
    private record PatientChatContext(
            ChatSession session,
            UserSnapshot user,
            List<Message> messages,
            String systemPrompt,
            String userMessage,
            String referenceJson,
            String emergencyReply) {
    }
}
