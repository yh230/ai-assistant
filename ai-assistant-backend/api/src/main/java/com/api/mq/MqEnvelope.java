package com.api.mq;

import lombok.Data;

/**
 * Kafka 消息统一信封（幂等键与元数据）
 *
 * @author yh
 * @date 2026-10-14
 */
@Data
public class MqEnvelope {

    /** 事件唯一ID（消费幂等键） */
    private String eventId;

    /** 事件类型码 */
    private String eventType;

    /** 发生时刻（毫秒） */
    private long occurredAt;

    /** 业务负载 JSON */
    private String payload;
}
