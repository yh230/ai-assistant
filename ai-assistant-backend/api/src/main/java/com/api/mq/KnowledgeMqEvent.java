package com.api.mq;

import lombok.Data;

/**
 * 知识库 Kafka 事件负载（异步向量化任务）
 *
 * @author yh
 * @date 2026-10-14
 */
@Data
public class KnowledgeMqEvent {

    /** 知识文档上传 */
    public static final String KNOWLEDGE_UPLOADED = "KNOWLEDGE_UPLOADED";

    /** 知识文档ID */
    private Long documentId;

    /** 医院ID（多租户隔离） */
    private Long hospitalId;
}
