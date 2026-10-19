package com.aiassistant.service.support;

import com.aiassistant.entity.ChatMessage;
import com.aiassistant.entity.ChatSession;
import com.aiassistant.repository.ChatMessageRepository;
import com.aiassistant.repository.ChatSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 聊天回复持久化组件单测：内容写入、会话刷新、空白跳过、异常吞掉与同会话串行
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class ChatMessagePersisterTest {

    @Mock
    private ChatMessageRepository messageRepository;

    @Mock
    private ChatSessionRepository sessionRepository;

    @Mock
    private TransactionTemplate txTemplate;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ChatMessagePersister persister;

    @BeforeEach
    void setUp() {
        persister = new ChatMessagePersister(messageRepository, sessionRepository, txTemplate, meterRegistry);
    }

    /**
     * 直接执行事务模板回调，避免依赖真实事务管理器
     */
    private void runTxCallbackInline() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }

    @Test
    void persistsAssistantMessageAndTouchesSession() {
        runTxCallbackInline();
        LocalDateTime before = LocalDateTime.now().minusDays(1);
        ChatSession session = ChatSession.builder().sessionId("s1").updatedAt(before).build();
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));

        persister.persistBlocking("s1", "hello", "[\"doc\"]");

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository).save(captor.capture());
        ChatMessage saved = captor.getValue();
        assertEquals("s1", saved.getSessionId());
        assertEquals("assistant", saved.getRole());
        assertEquals("hello", saved.getContent());
        assertEquals("[\"doc\"]", saved.getReferenceJson());
        assertTrue(session.getUpdatedAt().isAfter(before));
        verify(sessionRepository).save(session);
    }

    @Test
    void skipsNullAndBlankContent() {
        persister.persistBlocking("s1", null, null);
        persister.persistBlocking("s1", "", null);
        persister.persistBlocking("s1", "   ", null);

        verifyNoInteractions(messageRepository, sessionRepository, txTemplate);
    }

    @Test
    void persistsOnlyNonBlankContent() {
        runTxCallbackInline();
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.empty());

        persister.persistBlocking("s1", "hello", null);
        persister.persistBlocking("s1", "", null);

        verify(messageRepository, times(1)).save(any());
    }

    @Test
    void skipsWhenSessionIdMissing() {
        persister.persistBlocking(null, "hello", null);

        verifyNoInteractions(messageRepository, sessionRepository, txTemplate);
    }

    @Test
    void swallowsDbExceptionAndCountsFailure() {
        doThrow(new RuntimeException("db down")).when(txTemplate).executeWithoutResult(any());

        assertDoesNotThrow(() -> persister.persistBlocking("s1", "hello", null));

        assertEquals(1.0, meterRegistry.counter("ai.chat.persist.failures").count());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void reactivelyPersistsOnBoundedElastic() {
        runTxCallbackInline();
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> persister.persistAssistant("s1", "mono-content", null)
                .block(Duration.ofSeconds(5)));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository).save(captor.capture());
        assertEquals("mono-content", captor.getValue().getContent());
        assertNull(captor.getValue().getReferenceJson());
    }

    @Test
    void sameSessionPersistIsSerialized() throws Exception {
        runTxCallbackInline();
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.empty());

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(messageRepository.save(any())).thenAnswer(invocation -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } finally {
                active.decrementAndGet();
            }
            return invocation.getArgument(0);
        });

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    persister.persistBlocking("s1", "chunk", null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(1, maxActive.get());
        verify(messageRepository, times(threads)).save(any());
    }
}
