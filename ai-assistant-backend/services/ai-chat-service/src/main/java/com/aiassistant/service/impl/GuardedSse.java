package com.aiassistant.service.impl;

import com.aiassistant.safety.StreamingSafetyGuard;
import com.common.constant.ChatConstants;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式 token 风控适配：把 {@link StreamingSafetyGuard} 的输出映射为 SSE token 事件并累积正文
 * <p>
 * 守卫位于「token -> SSE」映射与正文累积之前，因此跨 token 拆分的关键词在下发前即被拦截，
 * 已泄露前缀不会进入 SSE、也不会留在落库累积器中；命中后守卫只下发一次标准文案，
 * 调用方据 {@code blocked} 标记改用标准文案落库并抑制引用下发。
 * 上游非安全类异常由守卫原样透传，超时/降级语义不受影响。
 *
 * @author yh
 * @date 2026-10-19
 */
final class GuardedSse {

    private GuardedSse() {
    }

    /**
     * 以流式安全守卫包装 token 流并映射为 SSE token 事件
     * <p>
     * 每条上游流新建一个守卫实例（守卫持有流内缓冲状态，不可跨流复用）；
     * 命中时除守卫自身下发标准文案外，还会置位 {@code blocked}，供落库与引用节点判定。
     *
     * @param tokens         原始 token 流（模型或本地降级）
     * @param full           回复正文累积器（仅追加实际下发内容）
     * @param blocked        输出侧拦截标记（命中后置为 true）
     * @param keywords       敏感词列表（来自 {@code ai.safety.keywords}）
     * @param blockedMessage 命中后的标准文案
     * @return SSE token 事件流
     */
    static Flux<ServerSentEvent<String>> guardedSse(Flux<String> tokens, StringBuffer full,
                                                    AtomicBoolean blocked, List<String> keywords,
                                                    String blockedMessage) {
        StreamingSafetyGuard.Guarded guarded =
                new StreamingSafetyGuard(keywords, blockedMessage).guard(tokens);
        return guarded.tokens()
                .map(token -> {
                    // 标准文案 token 到达时守卫已置位，先同步拦截标记再累积，落库节点可读到最终状态
                    if (guarded.blocked().get()) {
                        blocked.set(true);
                    }
                    full.append(token);
                    return ServerSentEvent.builder(token).event(ChatConstants.EVENT_TOKEN).build();
                })
                .doOnComplete(() -> {
                    if (guarded.blocked().get()) {
                        blocked.set(true);
                    }
                });
    }
}
