package com.aiassistant.service.impl;

import com.aiassistant.entity.KnowledgeDocument;
import com.aiassistant.mq.KnowledgeTaskPublisher;
import com.aiassistant.rag.KnowledgeVectorStore;
import com.aiassistant.repository.KnowledgeDocumentRepository;
import com.common.auth.UserSnapshot;
import com.common.constant.SecurityConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库上传事件提交后发送单测
 *
 * @author yh
 * @date 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceImplTest {

    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private KnowledgeVectorStore knowledgeVectorStore;
    @Mock private KnowledgeTaskPublisher taskPublisher;

    private KnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeServiceImpl(documentRepository, knowledgeVectorStore, taskPublisher);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("staff", null);
        auth.setDetails(UserSnapshot.builder().userId(7L).hospitalId(1L)
                .identity(SecurityConstants.IDENTITY_STAFF).build());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private MultipartFile file() {
        return new MockMultipartFile("file", "guide.md", "text/markdown",
                "正文".getBytes(StandardCharsets.UTF_8));
    }

    private void stubSave() {
        when(documentRepository.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument doc = invocation.getArgument(0);
            doc.setId(9L);
            return doc;
        });
    }

    @Test
    void publishesAfterCommitWhenSynchronizationActive() {
        stubSave();
        TransactionSynchronizationManager.initSynchronization();
        try {
            Long id = service.upload(file());

            assertEquals(9L, id);
            // 提交前不得发送，避免消费者读到未提交数据
            verify(taskPublisher, never()).publishUploaded(anyLong(), anyLong());
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());

            synchronizations.get(0).afterCommit();

            verify(taskPublisher, times(1)).publishUploaded(9L, 1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesImmediatelyWithoutSynchronization() {
        stubSave();
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        Long id = service.upload(file());

        assertEquals(9L, id);
        verify(taskPublisher, times(1)).publishUploaded(9L, 1L);
    }
}
