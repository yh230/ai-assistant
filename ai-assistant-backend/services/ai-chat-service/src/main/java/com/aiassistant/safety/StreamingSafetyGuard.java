package com.aiassistant.safety;

import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式安全守卫：holdback 缓冲跨 token 关键词，命中后终止上游并仅下发标准文案
 * <p>
 * 未命中时最多滞留 {@code maxKeywordLength - 1} 个字符，保证跨 token 的关键词（如 "胸" + "痛"）
 * 不会先于判定泄露给客户端；命中后立即取消上游，标准文案只下发一次且不再参与关键词扫描。
 * 上游非安全类异常原样透传，不在此吞掉。
 *
 * @author yh
 * @date 2026-10-19
 */
public class StreamingSafetyGuard {

    private final List<String> keywords;
    private final String blockedMessage;
    private final int holdback;

    /**
     * @param keywords         需要拦截的关键词列表（空白项自动忽略）
     * @param blockedMessage   命中后下发的标准文案
     * @param maxKeywordLength 关键词最大长度，用于计算 holdback（不足 1 时 holdback 为 0）
     */
    public StreamingSafetyGuard(List<String> keywords, String blockedMessage, int maxKeywordLength) {
        this.keywords = keywords == null ? List.of() : keywords.stream()
                .filter(StringUtils::hasText)
                .toList();
        this.blockedMessage = blockedMessage;
        this.holdback = Math.max(0, maxKeywordLength - 1);
    }

    /**
     * 包装上游 token 流
     *
     * @param upstream 原始 token 流
     * @return 守卫结果：脱敏后的 token 流、是否已拦截、实际下发内容
     */
    public Guarded guard(Flux<String> upstream) {
        StringBuilder pending = new StringBuilder();
        StringBuilder delivered = new StringBuilder();
        AtomicBoolean blocked = new AtomicBoolean(false);

        Flux<String> tokens = upstream
                .filter(StringUtils::hasText)
                .concatMap(token -> {
                    if (blocked.get()) {
                        return Flux.empty();
                    }
                    pending.append(token);
                    if (hit(pending)) {
                        blocked.set(true);
                        pending.setLength(0);
                        return Flux.error(new SafetyBlockedException());
                    }
                    int safe = Math.max(0, pending.length() - holdback);
                    if (safe == 0) {
                        return Flux.empty();
                    }
                    String emit = pending.substring(0, safe);
                    pending.delete(0, safe);
                    delivered.append(emit);
                    return Flux.just(emit);
                })
                .concatWith(Flux.defer(() -> {
                    if (blocked.get()) {
                        return Flux.empty();
                    }
                    String tail = pending.toString();
                    pending.setLength(0);
                    if (hit(tail)) {
                        // 命中发生在最后一个 token 的滞留区：同样只下发标准文案，且不回扫该文案
                        blocked.set(true);
                        delivered.setLength(0);
                        delivered.append(blockedMessage);
                        return Flux.just(blockedMessage);
                    }
                    if (tail.isEmpty()) {
                        return Flux.empty();
                    }
                    delivered.append(tail);
                    return Flux.just(tail);
                }))
                .onErrorResume(SafetyBlockedException.class, e -> {
                    delivered.setLength(0);
                    delivered.append(blockedMessage);
                    return Flux.just(blockedMessage);
                });

        return new Guarded(tokens, blocked, delivered);
    }

    private boolean hit(CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        String content = text.toString();
        return keywords.stream().anyMatch(content::contains);
    }

    /**
     * 守卫结果
     *
     * @param tokens    脱敏后的 token 流（命中时仅含一次标准文案）
     * @param blocked   是否已命中的标志（流执行后有效）
     * @param delivered 实际下发内容累积（流执行后有效）
     */
    public record Guarded(Flux<String> tokens, AtomicBoolean blocked, StringBuilder delivered) {
    }
}
