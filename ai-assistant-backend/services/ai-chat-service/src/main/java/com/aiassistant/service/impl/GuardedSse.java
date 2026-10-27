package com.aiassistant.service.impl;

import com.aiassistant.safety.StreamingSafetyGuard;
import com.common.constant.ChatConstants;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式 token 风控适配：把 {@link StreamingSafetyGuard} 的输出映射为 SSE token 事件并累积正文
 * <p>
 * 守卫位于「token -> SSE」映射与正文累积之前，因此跨 token 拆分的关键词在下发前即被拦截，
 * 已泄露前缀不会进入 SSE、也不会留在落库累积器中；命中后守卫只下发一次标准文案，
 * 调用方据 {@code blocked} 标记改用标准文案落库并抑制引用下发。
 * 上游非安全类异常由守卫原样透传，超时/降级语义不受影响。
 * <p>
 * <b>尾部滞留补偿</b>：守卫为跨 token 匹配会保守滞留一小段尾部。流被取消（客户端断开、
 * 下游 {@code take} 截断）时正常完成信号不会到达，若只依赖 token 映射累积，落库会丢掉这段尾部。
 * 因此这里在 {@code doOnComplete}/{@code doOnCancel} 上冲刷滞留尾部并追加到 {@code full}
 * （SSE 仍可能截断，但数据库保留已生成内容）；服务在自身取消回调中也可通过
 * {@link #flush(StreamingSafetyGuard.Guarded, StringBuffer, AtomicBoolean)} 主动冲刷，
 * 保证先冲刷再落库。
 *
 * @author yh
 * @date 2026-10-19
 */
final class GuardedSse {

    private GuardedSse() {
    }

    /**
     * 以流式安全守卫包装 token 流并映射为 SSE token 事件
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
        return guardedSse(tokens, full, blocked, keywords, blockedMessage, null, false);
    }

    /**
     * 以流式安全守卫包装 token 流并映射为 SSE token 事件，同时把守卫实例交给调用方
     * <p>
     * 每条上游流新建一个守卫实例（守卫持有流内缓冲状态，不可跨流复用）；
     * 命中时除守卫自身下发标准文案外，还会置位 {@code blocked}，供落库与引用节点判定。
     *
     * @param tokens         原始 token 流（模型或本地降级）
     * @param full           回复正文累积器（仅追加实际下发内容）
     * @param blocked        输出侧拦截标记（命中后置为 true）
     * @param keywords       敏感词列表（来自 {@code ai.safety.keywords}）
     * @param blockedMessage 命中后的标准文案
     * @param onGuard        守卫创建回调（可为 null）；调用方持有该实例以便在取消/落库前主动冲刷
     * @return SSE token 事件流
     */
    static Flux<ServerSentEvent<String>> guardedSse(Flux<String> tokens, StringBuffer full,
                                                    AtomicBoolean blocked, List<String> keywords,
                                                    String blockedMessage,
                                                    Consumer<StreamingSafetyGuard.Guarded> onGuard) {
        return guardedSse(tokens, full, blocked, keywords, blockedMessage, onGuard, false);
    }

    /**
     * 以流式安全守卫包装 token 流并映射为 SSE token 事件，可开启拒绝语境识别
     * <p>
     * 输出侧传 {@code refusalAware=true}：模型回复“不能提供用药建议”时属于正常拒答，不触发拦截；
     * 输入侧回显等场景保持 {@code false}，任何关键词出现都按命中处理。
     *
     * @param tokens         原始 token 流（模型或本地降级）
     * @param full           回复正文累积器（仅追加实际下发内容）
     * @param blocked        输出侧拦截标记（命中后置为 true）
     * @param keywords       敏感词列表（来自 {@code ai.safety.keywords}）
     * @param blockedMessage 命中后的标准文案
     * @param onGuard        守卫创建回调（可为 null）；调用方持有该实例以便在取消/落库前主动冲刷
     * @param refusalAware   是否识别拒绝语境（输出侧为 true）
     * @return SSE token 事件流
     */
    static Flux<ServerSentEvent<String>> guardedSse(Flux<String> tokens, StringBuffer full,
                                                    AtomicBoolean blocked, List<String> keywords,
                                                    String blockedMessage,
                                                    Consumer<StreamingSafetyGuard.Guarded> onGuard,
                                                    boolean refusalAware) {
        StreamingSafetyGuard.Guarded guarded =
                new StreamingSafetyGuard(keywords, blockedMessage, 0, refusalAware).guard(tokens);
        if (onGuard != null) {
            onGuard.accept(guarded);
        }
        return guarded.tokens()
                .map(token -> {
                    // 标准文案 token 到达时守卫已置位，先同步拦截标记再累积，落库节点可读到最终状态
                    if (guarded.blocked().get()) {
                        blocked.set(true);
                    }
                    full.append(token);
                    return ServerSentEvent.builder(token).event(ChatConstants.EVENT_TOKEN).build();
                })
                .doOnComplete(() -> flush(guarded, full, blocked))
                .doOnCancel(() -> flush(guarded, full, blocked));
    }

    /**
     * 冲刷守卫中尚未放行的尾部并同步拦截标记
     * <p>
     * 正常结束时尾部已被守卫自身补发，此处为空操作；取消/截断时把滞留尾部补入 {@code full}，
     * 避免落库丢失。命中判定在守卫内部完成，命中时追加的是标准文案。
     *
     * @param guarded 守卫实例（可为 null）
     * @param full    回复正文累积器
     * @param blocked 输出侧拦截标记
     */
    static void flush(StreamingSafetyGuard.Guarded guarded, StringBuffer full, AtomicBoolean blocked) {
        if (guarded == null) {
            return;
        }
        String tail = guarded.flushPending();
        if (!tail.isEmpty()) {
            full.append(tail);
        }
        if (guarded.blocked().get()) {
            blocked.set(true);
        }
    }
}
