package com.aiassistant.service.support;

import com.aiassistant.entity.ChatMessage;
import com.aiassistant.repository.ChatMessageRepository;
import com.aiassistant.repository.ChatSessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 聊天回复持久化：done 前完成、取消时落部分内容、同会话串行
 * <p>
 * 设计要点：
 * <ul>
 *     <li><b>时序</b>：{@link #persistAssistant(String, String, String)} 作为流内节点，done 事件在其完成后才下发；</li>
 *     <li><b>串行</b>：按 sessionId 哈希取条纹锁，同会话的落库请求串行执行，避免会话更新时间相互覆盖；</li>
 *     <li><b>容错</b>：DB 异常仅记录日志并累加 ai.chat.persist.failures 指标，不向上抛出，保证 done 事件仍可下发。</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-19
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessagePersister {

    /** 条纹锁数量（2 的幂，会话间尽量分散） */
    private static final int LOCK_STRIPES = 64;

    /** 落库失败指标名 */
    private static final String METRIC_PERSIST_FAILURES = "ai.chat.persist.failures";

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final TransactionTemplate txTemplate;
    private final MeterRegistry meterRegistry;

    /** 会话级条纹锁（初始化后只读） */
    private final ReentrantLock[] locks = createLocks();

    /**
     * 异步落库助手回复（运行于弹性线程池），供流内 persist 节点使用
     *
     * @param sessionId     会话ID
     * @param content       回复内容（空内容跳过）
     * @param referenceJson 引用来源 JSON（无来源传 null）
     * @return 落库完成信号（失败也被吞掉，正常结束）
     */
    public Mono<Void> persistAssistant(String sessionId, String content, String referenceJson) {
        return Mono.fromRunnable(() -> persistBlocking(sessionId, content, referenceJson))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 同步落库助手回复（供客户端取消路径使用）：空白内容跳过，DB 异常记录并计数后吞掉
     *
     * @param sessionId     会话ID
     * @param content       回复内容（空内容跳过）
     * @param referenceJson 引用来源 JSON（无来源传 null）
     */
    public void persistBlocking(String sessionId, String content, String referenceJson) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("回复落库跳过：会话ID为空");
            return;
        }
        ReentrantLock lock = lockFor(sessionId);
        lock.lock();
        try {
            txTemplate.executeWithoutResult(status -> {
                messageRepository.save(ChatMessage.builder()
                        .sessionId(sessionId).role("assistant").content(content)
                        .referenceJson(referenceJson).build());
                sessionRepository.findBySessionId(sessionId).ifPresent(s -> {
                    s.setUpdatedAt(LocalDateTime.now());
                    sessionRepository.save(s);
                });
            });
        } catch (RuntimeException ex) {
            meterRegistry.counter(METRIC_PERSIST_FAILURES).increment();
            log.error("回复落库失败 sessionId={}: {}", sessionId, ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 创建条纹锁数组
     *
     * @return 全部初始化的锁数组
     */
    private static ReentrantLock[] createLocks() {
        ReentrantLock[] lockArray = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < lockArray.length; i++) {
            lockArray[i] = new ReentrantLock();
        }
        return lockArray;
    }

    /**
     * 按会话标识取条纹锁（同一会话恒定同一把锁）
     *
     * @param sessionId 会话ID
     * @return 该会话对应的锁
     */
    private ReentrantLock lockFor(String sessionId) {
        return locks[Math.floorMod(sessionId.hashCode(), locks.length)];
    }
}
