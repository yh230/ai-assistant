package com.common.constant;

/**
 * 聊天模块通用常量集合
 * <p>
 * 遵循《阿里巴巴 Java 开发手册》：常量命名全部大写，单词间以下划线隔开；跨方法共享的魔法值统一收敛于此。
 *
 * @author yh
 * @date 2026-10-10
 */
public final class ChatConstants {

    private ChatConstants() {
    }

    /** SSE 事件：会话已创建，data 为 {"sessionId":"xxx","title":"xxx"} */
    public static final String EVENT_SESSION = "session";

    /** SSE 事件：流式文本片段，data 为单个 token 文本 */
    public static final String EVENT_TOKEN = "token";

    /** SSE 事件：流式结束，data 为 {"localFallback":false} */
    public static final String EVENT_DONE = "done";

    /** SSE 事件：发生错误，data 为 {"message":"xxx"} */
    public static final String EVENT_ERROR = "error";

    /** 最近 N 条消息作为完整上下文（不进行摘要压缩） */
    public static final int RECENT_CONTEXT_SIZE = 15;

    /** 历史消息超过此数量时触发摘要压缩 */
    public static final int SUMMARY_THRESHOLD = 25;

    /** 本地降级逐字推送间隔（毫秒） */
    public static final long LOCAL_STREAM_INTERVAL_MS = 35L;

    /** 本地降级每次推送字符数 */
    public static final int LOCAL_CHUNK_SIZE = 2;
}
