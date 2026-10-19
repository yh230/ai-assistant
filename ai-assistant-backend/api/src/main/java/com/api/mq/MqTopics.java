package com.api.mq;

/**
 * Kafka 主题与死信后缀常量
 *
 * @author yh
 * @date 2026-10-14
 */
public final class MqTopics {

    /** 预约域事件主题 */
    public static final String REGISTRATION_EVENT = "ai.registration.event";

    /** 操作审计日志主题 */
    public static final String AUDIT_LOG = "ai.audit.log";

    /** 知识库任务主题 */
    public static final String KNOWLEDGE_TASK = "ai.knowledge.task";

    /** 死信主题后缀 */
    public static final String DLT_SUFFIX = ".DLT";

    /** 知识库任务死信主题 */
    public static final String KNOWLEDGE_TASK_DLT = KNOWLEDGE_TASK + DLT_SUFFIX;

    private MqTopics() {
    }

    /**
     * 主题对应的死信主题名
     *
     * @param topic 原主题
     * @return 死信主题名
     */
    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
