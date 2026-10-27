package com.aiassistant.service.impl;

import com.aiassistant.config.AiProperties;
import com.aiassistant.config.AssistantProperties;
import com.aiassistant.config.ChatClientFactory;
import com.aiassistant.config.ChatModelRegistry;
import com.aiassistant.config.ModelProperties;
import com.aiassistant.config.ModelStreamExecutor;
import com.aiassistant.config.SceneOptionsFactory;
import com.aiassistant.dto.ChatRequest;
import com.aiassistant.dto.ChatResponse;
import com.aiassistant.dto.ModelInfo;
import com.aiassistant.dto.RoleInfo;
import com.aiassistant.dto.SessionDTO;
import com.aiassistant.dto.WeatherResponse;
import com.aiassistant.entity.ChatMessage;
import com.aiassistant.entity.ChatSession;
import com.aiassistant.memory.ConversationMemory;
import com.aiassistant.repository.ChatMessageRepository;
import com.aiassistant.repository.ChatSessionRepository;
import com.aiassistant.safety.OutputSafetyFilter;
import com.aiassistant.safety.StreamingSafetyGuard;
import com.aiassistant.service.ChatService;
import com.aiassistant.service.AssistantRoleService;
import com.aiassistant.service.UserMemoryBuilder;
import com.aiassistant.service.WeatherService;
import com.aiassistant.service.support.AiStreamSupport;
import com.aiassistant.service.support.ChatMessagePersister;
import com.aiassistant.service.support.ChatTextSupport;
import com.common.auth.UserSnapshot;
import com.common.constant.ChatConstants;
import com.common.constant.SecurityConstants;
import com.common.exception.BusinessException;
import com.common.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 聊天服务实现 - 基于 Spring AI ChatClient
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>Flux 流式响应</b>：{@code chatStream} 直接返回 {@code Flux<ServerSentEvent<String>}>，
 *         Spring MVC 自动适配为 SSE；阻塞型 DB 操作通过 {@code Schedulers.boundedElastic} 卸载，避免阻塞 IO 线程。</li>
 *     <li><b>多角色</b>：角色人设来自数据库（ai_role 表，可在「角色管理」页面维护），运行时按 roleId 选择，并注入跨会话的 DB 用户画像。</li>
 *     <li><b>多模型</b>：模型来自配置（ai.assistant.models），运行时按 modelId 选择 ChatClient；
 *         模型不可用时自动降级为本地回复引擎，对话不中断。</li>
 *     <li><b>上下文管理</b>：按 token 预算组装历史（{@link ConversationMemory}），超预算时保留最近消息并压缩早期消息为摘要；
 *         用户画像仅从当前登录人自身历史中提取。</li>
 *     <li><b>会话隔离</b>：会话按归属用户 + 身份绑定，员工侧读写一律限定当前登录人（identity=staff）的会话。</li>
 *     <li><b>输入输出风控</b>：输入命中敏感词直接拦截并落库标准文案；输出以流式守卫在 token 级拦截
 *         （含跨 token 拆分），命中后仅下发标准文案并按标准文案落库。</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    // ==================== 常量 ====================

    /** 城市提取正则 */
    private static final Pattern CITY_PATTERN =
            Pattern.compile("([\\u4e00-\\u9fa5]{2,8})(?:市|地区)?的?(?:今天|现在|明天|当前)?(?:实时)?(?:的)?天气");

    /** 用户画像注入分隔符 */
    private static final String MEMORY_SEPARATOR = "\n\n## 你对用户的了解（来自过去的对话记录）\n";

    /** 员工会话类型（员工侧统一为闲聊） */
    private static final String SESSION_TYPE_CHAT = "CHAT";

    // ==================== 依赖 ====================

    private final AssistantProperties assistantProperties;
    private final AiProperties aiProperties;
    private final AssistantRoleService assistantRoleService;
    private final ChatClientFactory chatClientFactory;
    private final ChatModelRegistry chatModelRegistry;
    private final ModelStreamExecutor modelStreamExecutor;
    private final SceneOptionsFactory sceneOptionsFactory;
    private final AiStreamSupport aiStreamSupport;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ConversationMemory conversationMemory;
    private final UserMemoryBuilder userMemoryBuilder;
    private final WeatherService weatherService;
    private final PlatformTransactionManager txManager;
    private final ObjectMapper objectMapper;
    private final ChatMessagePersister messagePersister;
    private final OutputSafetyFilter safetyFilter;

    @Value("${ai.local-fallback:true}")
    private boolean useLocalFallback;

    // ==================== 流式聊天（核心） ====================

    @Override
    public Flux<ServerSentEvent<String>> chatStream(ChatRequest request) {
        Assert.notNull(request, "请求参数不能为空");
        Assert.hasText(request.getMessage(), "消息内容不能为空");

        // 登录快照在请求线程捕获，随上下文传入弹性线程池（SecurityContext 为 ThreadLocal）
        UserSnapshot snapshot = currentStaffSnapshot();
        String requestedRoleId = assistantRoleService.resolveRoleId(request.getRoleId());
        String modelId = assistantProperties.resolveModelId(request.getModel());

        return Mono.fromCallable(() -> prepareContext(request, requestedRoleId, modelId, snapshot))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::buildReplyFlux)
                .onErrorResume(this::buildErrorFlux);
    }

    /**
     * 准备流式回复所需的上下文（阻塞型 DB 操作，运行在弹性线程池）。
     *
     * @param request         聊天请求
     * @param requestedRoleId 请求角色ID（已有会话以库中绑定为准）
     * @param modelId         解析后的模型ID
     * @param snapshot        当前登录人快照（请求线程捕获）
     * @return 聊天上下文
     */
    private ChatContext prepareContext(ChatRequest request, String requestedRoleId, String modelId, UserSnapshot snapshot) {
        ChatTextSupport.validateInput(aiProperties.getChat().getMaxInputChars(), request.getMessage());
        String sessionId = ensureSession(request, snapshot, requestedRoleId);
        ChatSession session = requireOwnedSession(sessionId, snapshot);
        String roleId = resolveEffectiveRoleId(session, requestedRoleId);
        if (session.getRoleId() == null || session.getRoleId().isBlank()) {
            session.setRoleId(roleId);
            sessionRepository.save(session);
        }
        String title = resolveTitle(request);
        saveUserMessage(sessionId, request.getMessage());

        // 输入侧风控：命中敏感词不调用大模型，落库标准文案后以 error 事件输出
        if (safetyFilter.blockedInput(request.getMessage())) {
            String blockedMessage = safetyFilter.blockedMessage();
            saveAssistantMessage(sessionId, blockedMessage);
            log.info("输入侧命中风控敏感词，拦截本次请求 sessionId={}", sessionId);
            throw new BusinessException(blockedMessage);
        }

        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Message> contextMessages = conversationMemory.build(session, history);
        String systemPrompt = buildSystemPrompt(assistantRoleService.resolveSystemPrompt(roleId),
                snapshot.getUserId(), memoryIdentity(snapshot));
        boolean aiEnabled = !chatModelRegistry.getFallbackChain(modelId).isEmpty();

        log.info("流式聊天 sessionId={}, roleId={}, modelId={}, aiEnabled={}, 历史消息数={}",
                sessionId, roleId, modelId, aiEnabled, history.size());

        return new ChatContext(sessionId, title, history, contextMessages, systemPrompt,
                request.getMessage(), roleId, modelId, aiEnabled);
    }

    /**
     * 构建完整回复流：会话事件 + 内容流（AI 或本地降级）+ 落库 + 结束事件。
     * <p>
     * 落库作为流内节点在 done 之前完成（不再 fire-and-forget），保证 done 到达时消息已可见；
     * AI 与本地降级 token 统一累积进同一个 {@code full}，客户端取消时按已产生的部分内容落库一次。
     *
     * @param ctx 聊天上下文
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> buildReplyFlux(ChatContext ctx) {
        ServerSentEvent<String> sessionEvent = ServerSentEvent.builder(
                        ChatTextSupport.toJson(objectMapper,
                                Map.of("sessionId", ctx.sessionId(), "title", ctx.title())))
                .event(ChatConstants.EVENT_SESSION)
                .build();

        // 取消回调可能在其他线程读取累积器，使用线程安全的 StringBuffer
        StringBuffer full = new StringBuffer();
        AtomicBoolean persisted = new AtomicBoolean(false);
        AtomicBoolean usedFallback = new AtomicBoolean(!ctx.aiEnabled());
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicBoolean blocked = new AtomicBoolean(false);
        // 守卫实例引用：取消时先冲刷滞留尾部再落库（取消信号自下游向上游传播，外层 doOnCancel 先于守卫自身执行）
        AtomicReference<StreamingSafetyGuard.Guarded> guardRef = new AtomicReference<>();
        Flux<ServerSentEvent<String>> contentFlux = ctx.aiEnabled()
                ? buildAiContentFlux(ctx, full, usedFallback, truncated, blocked, guardRef)
                : buildLocalChunkFlux(generateLocalResponse(ctx.userMessage(), ctx.history()), full, blocked,
                        guardRef);

        Mono<Void> persist = Mono.defer(() -> {
            if (!persisted.compareAndSet(false, true)) {
                return Mono.empty();
            }
            return messagePersister.persistAssistant(ctx.sessionId(), persistedBody(full, blocked), null);
        });

        Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("localFallback", usedFallback.get());
            if (truncated.get()) {
                payload.put("truncated", true);
            }
            return ServerSentEvent.builder(ChatTextSupport.toJson(objectMapper, payload))
                    .event(ChatConstants.EVENT_DONE)
                    .build();
        });

        return Flux.concat(Mono.just(sessionEvent), contentFlux, persist.then(doneEvent))
                .doOnCancel(() -> {
                    // 先冲刷守卫滞留尾部再落库，避免客户端断开时丢失最后一段已生成内容
                    GuardedSse.flush(guardRef.get(), full, blocked);
                    if (persisted.compareAndSet(false, true) && (blocked.get() || full.length() > 0)) {
                        messagePersister.persistBlocking(ctx.sessionId(), persistedBody(full, blocked), null);
                    }
                });
    }

    /**
     * 计算落库正文：流式风控命中时只落标准拦截文案，绝不落原始（可能不安全的）部分正文。
     *
     * @param full    回复正文累积器
     * @param blocked 输出侧流式风控拦截标记
     * @return 可落库的回复正文
     */
    private String persistedBody(StringBuffer full, AtomicBoolean blocked) {
        return blocked.get() ? safetyFilter.blockedMessage() : full.toString();
    }

    /**
     * 构建 AI 模型流式内容：多模型故障转移 + 场景参数 + 空闲/总超时 + 用量计量，
     * 逐 token 推送并累积，失败时降级本地引擎继续累积。
     * <p>
     * 总超时由 {@code take} 正常结束（无异常信号），按耗时判定截断并置位 {@code truncated}，
     * 由 done 事件透出，同时计入失败与截断指标。
     *
     * @param ctx          聊天上下文
     * @param full         回复内容累积器（AI token 与降级 token 共用）
     * @param usedFallback 降级标记
     * @param truncated    总超时截断标记（供 done 事件上报）
     * @param blocked      输出侧流式风控拦截标记（守卫命中时置位）
     * @param guardRef     守卫实例引用（取消/落库前冲刷滞留尾部）
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> buildAiContentFlux(ChatContext ctx, StringBuffer full,
                                                            AtomicBoolean usedFallback, AtomicBoolean truncated,
                                                            AtomicBoolean blocked,
                                                            AtomicReference<StreamingSafetyGuard.Guarded> guardRef) {
        String scene = "chat";
        List<String> chain = chatModelRegistry.getFallbackChain(ctx.modelId());
        boolean emptyChain = chain.isEmpty();
        String primaryModel = emptyChain ? ctx.modelId() : chain.get(0);
        Duration overallTimeout = Duration.ofSeconds(aiProperties.getChat().getOverallTimeoutSeconds());
        long startNanos = System.nanoTime();

        Flux<String> tokens = modelStreamExecutor.stream(chain, candidate ->
                aiStreamSupport.tokenFlux(candidate, scene, () ->
                        chatClientFactory.getChatClient(candidate).prompt()
                                .system(ctx.systemPrompt())
                                .messages(ctx.messages())
                                .options(sceneOptionsFactory.options(scene))
                                .stream()
                                .chatResponse()));

        return GuardedSse.guardedSse(tokens.publishOn(Schedulers.boundedElastic()), full, blocked,
                        aiProperties.getSafety().getKeywords(), aiProperties.getSafety().getBlockedMessage(),
                        guardRef::set)
                .timeout(Duration.ofSeconds(aiProperties.getChat().getIdleTimeoutSeconds()))
                .take(overallTimeout)
                .doOnComplete(() -> {
                    if (System.nanoTime() - startNanos >= overallTimeout.toNanos()) {
                        truncated.set(true);
                        aiStreamSupport.recordFailure(primaryModel, scene, "OverallTimeout");
                        aiStreamSupport.recordTruncated(primaryModel, scene);
                        log.warn("AI 生成达总超时（{} 秒）被截断: sessionId={}",
                                aiProperties.getChat().getOverallTimeoutSeconds(), ctx.sessionId());
                    }
                })
                .onErrorResume(ex -> {
                    if (ex instanceof TimeoutException) {
                        aiStreamSupport.recordFailure(primaryModel, scene, "IdleTimeout");
                    } else if (ex instanceof ModelStreamExecutor.ModelChainExhaustedException && emptyChain) {
                        // 有候选模型时失败已由候选级 doOnError 计数，此处不再重复计；仅空链无候选，计数一次
                        aiStreamSupport.recordFailure(primaryModel, scene, "EmptyChain");
                    }
                    log.warn("AI 流式响应异常，降级本地引擎: {}", ex.getMessage());
                    usedFallback.set(true);
                    return buildLocalChunkFlux(generateLocalResponse(ctx.userMessage(), ctx.history()),
                            full, blocked, guardRef);
                });
    }

    /**
     * 将文本切片为 SSE token 流（本地降级复用）：token 先经流式风控守卫，
     * 再映射为 SSE 并累积正文，不落库。
     *
     * @param text     完整文本
     * @param full     回复内容累积器
     * @param blocked  输出侧流式风控拦截标记
     * @param guardRef 守卫实例引用（取消/落库前冲刷滞留尾部，可为 null）
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> buildLocalChunkFlux(String text, StringBuffer full,
                                                              AtomicBoolean blocked,
                                                              AtomicReference<StreamingSafetyGuard.Guarded> guardRef) {
        List<String> chunks = ChatTextSupport.splitIntoChunks(text, ChatConstants.LOCAL_CHUNK_SIZE);
        return GuardedSse.guardedSse(
                Flux.fromIterable(chunks).delayElements(java.time.Duration.ofMillis(ChatConstants.LOCAL_STREAM_INTERVAL_MS)),
                full, blocked, aiProperties.getSafety().getKeywords(), aiProperties.getSafety().getBlockedMessage(),
                guardRef == null ? null : guardRef::set);
    }

    /**
     * 顶层异常处理：转换为 SSE error 事件。
     */
    private Flux<ServerSentEvent<String>> buildErrorFlux(Throwable ex) {
        log.error("流式聊天处理失败", ex);
        ServerSentEvent<String> errorEvent = ServerSentEvent.builder(
                        ChatTextSupport.toJson(objectMapper,
                                Map.of("message", ex.getMessage() != null ? ex.getMessage() : "未知错误")))
                .event(ChatConstants.EVENT_ERROR)
                .build();
        return Flux.just(errorEvent);
    }

    // ==================== 非流式聊天（兼容旧接口） ====================

    @Override
    @Transactional
    public ChatResponse chat(ChatRequest request) {
        Assert.notNull(request, "请求参数不能为空");
        Assert.hasText(request.getMessage(), "消息内容不能为空");
        ChatTextSupport.validateInput(aiProperties.getChat().getMaxInputChars(), request.getMessage());

        UserSnapshot snapshot = currentStaffSnapshot();
        String requestedRoleId = assistantRoleService.resolveRoleId(request.getRoleId());
        String modelId = assistantProperties.resolveModelId(request.getModel());

        String sessionId = ensureSession(request, snapshot, requestedRoleId);
        ChatSession session = requireOwnedSession(sessionId, snapshot);
        String roleId = resolveEffectiveRoleId(session, requestedRoleId);
        if (session.getRoleId() == null || session.getRoleId().isBlank()) {
            session.setRoleId(roleId);
            sessionRepository.save(session);
        }
        String systemPrompt = buildSystemPrompt(assistantRoleService.resolveSystemPrompt(roleId),
                snapshot.getUserId(), memoryIdentity(snapshot));
        saveUserMessage(sessionId, request.getMessage());
        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        String reply;
        boolean usedFallback = false;

        if (chatModelRegistry.isActive(modelId)) {
            try {
                reply = callAi(systemPrompt, session, history, modelId);
            } catch (Exception e) {
                log.warn("AI 调用失败，切换本地回复: {}", e.getMessage());
                if (useLocalFallback) {
                    reply = generateLocalResponse(request.getMessage(), history);
                    usedFallback = true;
                } else {
                    reply = "哎呀，我这边信号不太好（AI 服务暂时不可用），稍后再找我聊吧~";
                }
            }
        } else {
            reply = generateLocalResponse(request.getMessage(), history);
            usedFallback = true;
        }

        saveAssistantMessage(sessionId, reply);
        return buildChatResponse(sessionId, reply, usedFallback);
    }

    /**
     * 调用指定模型生成完整回复（非流式）。
     */
    private String callAi(String systemPrompt, ChatSession session, List<ChatMessage> history, String modelId) {
        ChatClient client = chatClientFactory.getChatClient(modelId);
        List<Message> messages = conversationMemory.build(session, history);
        return client.prompt()
                .system(systemPrompt)
                .messages(messages)
                .call()
                .content();
    }

    // ==================== 角色与模型列表 ====================

    @Override
    public List<RoleInfo> listRoles() {
        return assistantRoleService.listEnabledOrdered().stream()
                .map(r -> RoleInfo.builder()
                        .id(r.getId())
                        .name(r.getName())
                        .avatar(r.getAvatar())
                        .description(r.getDescription())
                        .build())
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<ModelInfo> listModels() {
        List<ModelInfo> result = new ArrayList<>(assistantProperties.getModels().size());
        for (Map.Entry<String, ModelProperties> entry : assistantProperties.getModels().entrySet()) {
            ModelProperties props = entry.getValue();
            String label = (props != null && props.getModel() != null) ? props.getModel() : entry.getKey();
            result.add(ModelInfo.builder()
                    .id(entry.getKey())
                    .label(label)
                    .active(chatModelRegistry.isActive(entry.getKey()))
                    .build());
        }
        return result;
    }

    // ==================== 动态系统提示词（用户画像注入） ====================

    /**
     * 构建最终系统提示词 = 角色人设 + 当前登录人的跨会话画像
     *
     * @param systemPrompt 角色系统提示词（来自数据库，已含兜底默认值）
     * @param userId       登录人 ID
     * @param identity     身份（staff/patient）
     * @return 系统提示词
     */
    private String buildSystemPrompt(String systemPrompt, Long userId, String identity) {
        String base = (systemPrompt != null && !systemPrompt.isBlank())
                ? systemPrompt : "你是用户的 AI 助手，请友好、简洁、真诚地回应。";
        String memory = userMemoryBuilder.build(userId, identity);
        if (memory != null && !memory.isBlank()) {
            return base + MEMORY_SEPARATOR + memory;
        }
        return base;
    }

    /**
     * 解析画像查询使用的身份：空值按旧数据员工处理
     *
     * @param snapshot 当前登录人快照
     * @return 身份（staff/patient）
     */
    private String memoryIdentity(UserSnapshot snapshot) {
        String identity = snapshot.getIdentity();
        return (identity == null || identity.isBlank()) ? SecurityConstants.IDENTITY_STAFF : identity;
    }

    // ==================== 历史与列表 ====================

    @Override
    @Transactional(readOnly = true)
    public ChatResponse getHistory(String sessionId) {
        requireOwnedSession(sessionId);
        List<ChatResponse.ChatMessageDTO> messageDTOs =
                messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                        .map(m -> ChatResponse.ChatMessageDTO.builder()
                                .role(m.getRole()).content(m.getContent()).createdAt(m.getCreatedAt()).build())
                        .collect(Collectors.toList());
        return ChatResponse.builder()
                .sessionId(sessionId).messages(messageDTOs)
                .timestamp(LocalDateTime.now()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionDTO> getSessionList() {
        UserSnapshot snapshot = currentStaffSnapshot();
        return sessionRepository.findAllByUserIdAndIdentityOrderByUpdatedAtDesc(
                        snapshot.getUserId(), SecurityConstants.IDENTITY_STAFF).stream()
                .map(s -> SessionDTO.builder()
                        .sessionId(s.getSessionId()).title(s.getTitle())
                        .sessionType(s.getSessionType()).updatedAt(s.getUpdatedAt()).build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        ChatSession session = requireOwnedSession(sessionId);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
    }

    @Override
    @Transactional
    public ChatResponse createSession(String title) {
        UserSnapshot snapshot = currentStaffSnapshot();
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String sessionTitle = (title == null || title.trim().isEmpty()) ? "新对话" : title;
        createSessionEntity(sessionId, sessionTitle, snapshot, null);
        return ChatResponse.builder()
                .sessionId(sessionId).timestamp(LocalDateTime.now()).build();
    }

    // ==================== DB 辅助 ====================

    /**
     * 确保会话存在且归属当前登录员工：携带 sessionId 时校验归属，否则新建并绑定归属与人设
     *
     * @param request  聊天请求
     * @param snapshot 当前登录人快照
     * @param roleId   解析后的角色ID（新会话写入）
     * @return 会话标识
     */
    private String ensureSession(ChatRequest request, UserSnapshot snapshot, String roleId) {
        String sessionId = request.getSessionId();
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            requireOwnedSession(sessionId, snapshot);
            return sessionId;
        }
        String newId = UUID.randomUUID().toString().replace("-", "");
        createSessionEntity(newId, resolveTitle(request), snapshot, roleId);
        return newId;
    }

    /**
     * 解析会话实际人设：已有会话以库中 role_id 为准，缺失时回退请求值
     *
     * @param session 会话实体（可为空）
     * @param requestedRoleId 请求角色 ID（可为空）
     * @return 实际角色 ID
     */
    String resolveEffectiveRoleId(ChatSession session, String requestedRoleId) {
        if (session != null && session.getRoleId() != null && !session.getRoleId().isBlank()) {
            return session.getRoleId();
        }
        return assistantRoleService.resolveRoleId(requestedRoleId);
    }

    /**
     * 获取当前登录人快照（员工侧接口位于认证过滤器之后，快照缺失按未登录处理）
     * <p>
     * 患者令牌直连服务端口时快照 identity=patient，非员工身份直接拒绝；
     * identity 为空视为旧数据员工（Ruling-1 语义）。
     *
     * @return 当前登录人快照
     */
    private UserSnapshot currentStaffSnapshot() {
        UserSnapshot snapshot = SecurityUtils.currentSnapshot();
        if (snapshot == null) {
            throw new BusinessException("未登录");
        }
        if (snapshot.getIdentity() != null && !SecurityConstants.IDENTITY_STAFF.equals(snapshot.getIdentity())) {
            throw new BusinessException("无权访问");
        }
        return snapshot;
    }

    /**
     * 校验会话归属并返回实体（异步线程池内使用请求线程捕获的快照）
     *
     * @param sessionId 会话标识
     * @param snapshot  当前登录人快照
     * @return 归属当前登录人的会话实体
     */
    private ChatSession requireOwnedSession(String sessionId, UserSnapshot snapshot) {
        return sessionRepository.findBySessionIdAndUserIdAndIdentity(sessionId, snapshot.getUserId(),
                        SecurityConstants.IDENTITY_STAFF)
                .orElseThrow(() -> new BusinessException("会话不存在或无权访问"));
    }

    /**
     * 校验会话归属当前登录员工并返回实体
     *
     * @param sessionId 会话标识
     * @return 归属当前登录人的会话实体
     */
    private ChatSession requireOwnedSession(String sessionId) {
        return requireOwnedSession(sessionId, currentStaffSnapshot());
    }

    /**
     * 解析会话标题：优先使用请求传入的标题，否则用首条消息生成。
     */
    private String resolveTitle(ChatRequest request) {
        if (request.getTitle() != null && !request.getTitle().trim().isEmpty()) {
            return request.getTitle();
        }
        return generateTitle(request.getMessage());
    }

    /**
     * 落库用户消息（事务内）。
     */
    private void saveUserMessage(String sessionId, String content) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                messageRepository.save(ChatMessage.builder()
                        .sessionId(sessionId).role("user").content(content).build()));
    }

    /**
     * 落库 AI 回复（事务内）并更新会话时间（非流式兼容接口使用）。
     */
    private void saveAssistantMessage(String sessionId, String content) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            messageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId).role("assistant").content(content).build());
            sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
                s.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(s);
            });
        });
    }

    /**
     * 创建归属当前登录员工的会话实体（员工侧会话类型固定为闲聊）
     *
     * @param sessionId 会话标识
     * @param title     会话标题
     * @param snapshot  当前登录人快照
     * @param roleId    绑定的人设ID（可为空）
     */
    private void createSessionEntity(String sessionId, String title, UserSnapshot snapshot, String roleId) {
        sessionRepository.save(ChatSession.builder()
                .sessionId(sessionId)
                .title(title)
                .userId(snapshot.getUserId())
                .identity(SecurityConstants.IDENTITY_STAFF)
                .sessionType(SESSION_TYPE_CHAT)
                .hospitalId(snapshot.getHospitalId())
                .roleId(roleId)
                .build());
    }

    /**
     * 组装完整聊天响应。
     */
    private ChatResponse buildChatResponse(String sessionId, String reply, boolean usedFallback) {
        List<ChatResponse.ChatMessageDTO> dtos = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(m -> ChatResponse.ChatMessageDTO.builder()
                        .role(m.getRole()).content(m.getContent()).createdAt(m.getCreatedAt()).build())
                .collect(Collectors.toList());
        return ChatResponse.builder()
                .sessionId(sessionId).reply(reply)
                .timestamp(LocalDateTime.now()).localFallback(usedFallback)
                .messages(dtos).build();
    }

    /**
     * 生成会话标题（取消息前 20 字）。
     */
    private String generateTitle(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "新对话";
        }
        return message.length() > 20 ? message.substring(0, 20) + "..." : message;
    }

    // ==================== 本地回复引擎（降级方案） ====================

    /**
     * 生成本地回复（无可用模型时兜底，支持天气/解闷/闲聊等）
     *
     * @param userMessage 用户消息
     * @param history 历史消息列表
     * @return 本地生成的回复文本
     */
    private String generateLocalResponse(String userMessage, List<ChatMessage> history) {
        String msg = userMessage == null ? "" : userMessage.trim().toLowerCase();

        if (msg.contains("天气") || msg.contains("气温") || msg.contains("温度") || msg.contains("下雨")
                || msg.contains("weather") || msg.contains("多少度")) {
            return answerWeatherLocally(userMessage);
        }
        if (msg.contains("你好") || msg.contains("hello") || msg.contains("hi") || msg.contains("嗨")
                || msg.contains("早上好") || msg.contains("晚上好") || msg.contains("下午好")) {
            return "嗨~ 我是你的聊天小伙伴「小伴」😊 很高兴见到你！想聊点什么？无聊了可以让我讲个笑话，想查天气直接报城市名就行~";
        }
        if (msg.contains("你是谁") || msg.contains("你叫什么") || msg.contains("自我介绍")) {
            return "我是你的聊天小伙伴「小伴」呀！基于 Spring AI 打造（Spring Boot + Vue 3 + MySQL）。我的日常就是陪你聊天解闷、倾听你的心事，还会查天气提醒你添衣带伞~ ☂️";
        }
        if (msg.contains("笑话") || msg.contains("无聊") || msg.contains("解闷") || msg.contains("逗我")) {
            String[] jokes = {
                    "给你讲个冷笑话：为什么程序员不喜欢大自然？因为那里有太多 bug（虫子）啦 🐛",
                    "来一个：小明问妈妈\"为什么我这么黑？\"妈妈说：\"等你长大了就知道，黑是因为你省了防晒霜的钱。\"",
                    "冷笑话来咯：冰雪奇缘里最冷静的人是谁？当然是 Elsa，毕竟她全身都是冰 🧊",
                    "问你个脑筋急转弯：什么东西越洗越脏？——答案是水！",
                    "来段绕口令：吃葡萄不吐葡萄皮，不吃葡萄倒吐葡萄皮。来，你试试连说三遍 😝",
                    "问：什么动物最容易摔倒？答：狐狸，因为它狡猾（脚滑）🦊",
                    "为什么海最守信用？因为它有海誓（海绵） 😂"
            };
            return jokes[new java.util.Random().nextInt(jokes.length)];
        }
        if (msg.contains("谢谢") || msg.contains("thanks") || msg.contains("感谢")) {
            return "不客气呀~ 能陪到你就很开心！有需要随时喊我 😊";
        }
        if (msg.contains("再见") || msg.contains("bye") || msg.contains("拜拜") || msg.contains("晚安")) {
            return "拜拜~ 记得想我哦！祝你今天顺顺利利的 🌟";
        }
        if (msg.contains("能做什么") || msg.contains("功能") || msg.contains("会什么")) {
            return "我可是个多才多艺的小伙伴：\n1. **陪你聊天** - 闲聊解闷、倾听心事、讲笑话\n2. **查天气** - 报个城市名，实时天气+5天预报安排上\n\n配置 AI API Key 之后我会变得更聪明、更会聊天哦！";
        }
        if (msg.contains("几点") || msg.contains("时间") || msg.contains("日期")) {
            return "现在是 " + LocalDateTime.now().toString().replace("T", " ").substring(0, 16) + "。时间过得真快呀~";
        }
        if (msg.matches(".*\\d+\\s*[+\\-*/]\\s*\\d+.*")) {
            try {
                String expr = msg.replaceAll("[^0-9+\\-*/. ]", "");
                double result = evalExpression(expr.trim());
                return "算好啦：" + expr.trim() + " = " + formatNumber(result) + "，这种小事难不倒我 😎";
            } catch (Exception e) {
                log.debug("忽略非关键异常: {}", e.getMessage());
            }
        }
        if (msg.contains("难过") || msg.contains("伤心") || msg.contains("不开心") || msg.contains("累")
                || msg.contains("烦") || msg.contains("压力大") || msg.contains("emo")) {
            return "抱抱你 🤗 辛苦啦。生活有时候确实挺难的，但你已经做得很好了。要不我给你讲个笑话换换心情？或者查查明天天气，出门走走透透气？";
        }
        if (msg.contains("开心") || msg.contains("高兴") || msg.contains("快乐") || msg.contains("哈哈")) {
            return "看到你这么开心，我也跟着乐呵呵的 😄 快乐就要分享，说来听听是什么好事呀？";
        }
        if (msg.contains("刚才") || msg.contains("上面") || msg.contains("你说的")) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("assistant".equals(history.get(i).getRole())) {
                    return "我刚才说的是：" + history.get(i).getContent() + "\n\n咱们继续聊这个话题吗？";
                }
            }
        }
        return "嗯嗯，我在认真听你说：「" + userMessage + "」\n\n" +
                "不过我现在是本地省电模式（没连上大模型），脑子转得慢一些 😅 配置好 API Key 我就能畅聊任何话题啦。\n\n" +
                "现在我可以：陪你唠嗑解闷、讲笑话、算算数、查天气（报城市名就行）~";
    }

    /**
     * 本地天气回答（真实调用天气服务）。
     */
    private String answerWeatherLocally(String userMessage) {
        String city = extractCity(userMessage);
        if (city == null) {
            return "想查哪个城市的天气呀？告诉我城市名，比如「北京天气」「上海今天多少度」，我马上帮你查 ☀️";
        }

        WeatherResponse w = weatherService.getWeather(city);
        if (w == null || !w.isSuccess()) {
            return "唔，没查到「" + city + "」的天气，" +
                    (w != null && w.getError() != null ? w.getError() : "换个城市名试试？") + " 🤔";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s现在%s，气温 %.1f℃", w.getCity(), w.getWeatherDescription(), w.getCurrentTemperature()));
        if (w.getApparentTemperature() != null) {
            sb.append(String.format("（体感 %.1f℃）", w.getApparentTemperature()));
        }
        if (w.getHumidity() != null) {
            sb.append(String.format("，湿度 %d%%", w.getHumidity()));
        }
        if (w.getWindSpeed() != null) {
            sb.append(String.format("，%s %.1fkm/h", w.getWindDirection(), w.getWindSpeed()));
        }
        sb.append("。");

        double temp = w.getCurrentTemperature();
        if (w.getPrecipitation() != null && w.getPrecipitation() > 0) {
            sb.append("外面在下雨，出门记得带伞哦 ☔");
        } else if (temp <= 5) {
            sb.append("天儿挺冷的，多穿点别冻着~ 🧣");
        } else if (temp >= 32) {
            sb.append("有点热，注意防暑多喝水 🥤");
        } else {
            sb.append("天气还不错，适合出去走走 😊");
        }

        if (w.getDailyForecasts() != null && w.getDailyForecasts().size() > 1) {
            WeatherResponse.DailyForecast tomorrow = w.getDailyForecasts().get(1);
            sb.append(String.format("明天%s，%.0f~%.0f℃。",
                    tomorrow.getDescription(), tomorrow.getMinTemperature(), tomorrow.getMaxTemperature()));
        }
        return sb.toString();
    }

    /**
     * 从消息中提取城市名。
     */
    private String extractCity(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = CITY_PATTERN.matcher(message);
        if (matcher.find()) {
            String city = matcher.group(1);
            if (city.matches("(查|问|看|找|这|那|个|的|我|你|他|她|今|明|后|白|晚)+")) {
                return null;
            }
            return city;
        }
        return null;
    }

    /**
     * 简易四则运算求值（支持 + - * / 与括号）。
     */
    private double evalExpression(String expr) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < expr.length()) ? expr.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') {
                    nextChar();
                }
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expr.length()) {
                    throw new RuntimeException("表达式非法: " + expr);
                }
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                while (true) {
                    if (eat('+')) {
                        x += parseTerm();
                    } else if (eat('-')) {
                        x -= parseTerm();
                    } else {
                        return x;
                    }
                }
            }

            double parseTerm() {
                double x = parseFactor();
                while (true) {
                    if (eat('*')) {
                        x *= parseFactor();
                    } else if (eat('/')) {
                        x /= parseFactor();
                    } else {
                        return x;
                    }
                }
            }

            double parseFactor() {
                if (eat('+')) {
                    return parseFactor();
                }
                if (eat('-')) {
                    return -parseFactor();
                }
                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') {
                        nextChar();
                    }
                    x = Double.parseDouble(expr.substring(startPos, this.pos));
                } else {
                    throw new RuntimeException("表达式非法: " + expr);
                }
                return x;
            }
        }.parse();
    }

    /**
     * 数字格式化（整数去小数）。
     */
    private String formatNumber(double value) {
        return (value == (long) value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    // ==================== 内部上下文记录 ====================

    /**
     * 流式聊天上下文（不可变值对象）。
     *
     * @param sessionId   会话ID
     * @param title       会话标题
     * @param history     完整历史消息（实体）
     * @param messages    组装好的 Spring AI 上下文消息
     * @param systemPrompt 最终系统提示词
     * @param userMessage 当前用户消息
     * @param roleId      角色ID
     * @param modelId     模型ID
     * @param aiEnabled   模型是否可用
     */
    private record ChatContext(
            String sessionId,
            String title,
            List<ChatMessage> history,
            List<Message> messages,
            String systemPrompt,
            String userMessage,
            String roleId,
            String modelId,
            boolean aiEnabled) {
    }
}
