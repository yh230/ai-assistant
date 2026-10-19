package com.aiassistant.safety;

import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.text.Normalizer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 流式安全守卫：holdback 缓冲跨 token 关键词，命中后终止上游并仅下发标准文案
 * <p>
 * 关键词与缓冲文本统一经 NFKC + 去空白归一后再匹配（下发始终是原始文本），
 * 因此 "用　药建议"、"胸 痛" 以及被空格/全角空格拆分到多个 token 的关键词都不能绕过。
 * 未命中时按原始字符保守滞留（至少 {@code 2 * maxKeywordLength} 个原始字符，且保证归一后保留
 * 内容的长度不小于 {@code maxKeywordLength - 1}），既保证跨 token 关键词不会提前泄露，
 * 也保证被归一移除的空格/全角字符不会把关键词藏在已下发前缀中。
 * 命中后立即取消上游，标准文案只下发一次且不再参与关键词扫描。
 * 上游非安全类异常原样透传，不在此吞掉。
 * <p>
 * 注意：守卫实例持有本次流的 {@link StringBuilder} 状态，<b>不是可重复订阅（re-subscription-safe）</b>
 * 组件；每条上游流都应调用 {@link #guard(Flux)} 新建一个 {@link Guarded}，且该结果只应订阅一次。
 *
 * @author yh
 * @date 2026-10-19
 */
public class StreamingSafetyGuard {

    /** 归一用空白（含全角空格 U+3000） */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u3000]+");

    /** 归一后的关键词列表 */
    private final List<String> keywords;

    private final String blockedMessage;

    /** 归一后关键词的最大长度 */
    private final int maxKeywordLength;

    /** 未命中时保留在缓冲区中的原始字符数（保守取值） */
    private final int holdback;

    /**
     * 使用关键词列表内部计算的最大长度构造守卫（推荐）。
     *
     * @param keywords       需要拦截的关键词列表（空白项自动忽略）
     * @param blockedMessage 命中后下发的标准文案（为 null 时下发空串）
     */
    public StreamingSafetyGuard(List<String> keywords, String blockedMessage) {
        this(keywords, blockedMessage, 0);
    }

    /**
     * 显式指定关键词最大长度（主要供测试使用）。
     *
     * @param keywords         需要拦截的关键词列表（空白项自动忽略）
     * @param blockedMessage   命中后下发的标准文案（为 null 时下发空串）
     * @param maxKeywordLength 关键词最大长度；内部会与词表实际最大归一长度取较大值，避免漏检
     */
    public StreamingSafetyGuard(List<String> keywords, String blockedMessage, int maxKeywordLength) {
        List<String> normalizedKeywords = (keywords == null ? List.<String>of() : keywords).stream()
                .filter(StringUtils::hasText)
                .map(StreamingSafetyGuard::normalize)
                .filter(keyword -> !keyword.isEmpty())
                .toList();
        this.keywords = normalizedKeywords;
        this.blockedMessage = blockedMessage;
        int actualMax = normalizedKeywords.stream().mapToInt(String::length).max().orElse(0);
        this.maxKeywordLength = Math.max(Math.max(0, maxKeywordLength), actualMax);
        // 保守 holdback：原始字符取 2 倍最长关键词，避免被归一删除的空格/全角字符掩盖关键词
        this.holdback = this.keywords.isEmpty() ? 0 : Math.max(1, this.maxKeywordLength * 2);
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
        // 文案为 null 时下发空串，避免把字面量 "null" 推给客户端
        String fallback = blockedMessage == null ? "" : blockedMessage;

        Flux<String> tokens = upstream
                // 仅丢弃 null/空串，空白 token 也要原样下发
                .filter(token -> token != null && !token.isEmpty())
                .concatMap(token -> {
                    if (blocked.get()) {
                        return Flux.empty();
                    }
                    pending.append(token);
                    if (hit(pending.toString())) {
                        blocked.set(true);
                        pending.setLength(0);
                        return Flux.error(new SafetyBlockedException());
                    }
                    int safe = safePrefixLength(pending.toString());
                    if (safe <= 0) {
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
                        delivered.append(fallback);
                        return Flux.just(fallback);
                    }
                    if (tail.isEmpty()) {
                        return Flux.empty();
                    }
                    delivered.append(tail);
                    return Flux.just(tail);
                }))
                .onErrorResume(SafetyBlockedException.class, e -> {
                    delivered.setLength(0);
                    delivered.append(fallback);
                    return Flux.just(fallback);
                });

        return new Guarded(tokens, blocked, delivered);
    }

    /**
     * 计算当前缓冲区中可以安全下发的原始字符前缀长度。
     * <p>
     * 必须同时满足：原始 holdback 下限，以及保留后缀归一后长度不小于 {@code maxKeywordLength - 1}，
     * 后者保证已下发文本不会以某个关键词的真前缀结尾（否则后续 token 补全关键词时前缀已泄露）。
     */
    private int safePrefixLength(String pending) {
        if (keywords.isEmpty()) {
            return pending.length();
        }
        int need = Math.max(0, maxKeywordLength - 1);
        if (normalize(pending).length() <= need) {
            return 0;
        }
        int rawSafe = Math.max(0, pending.length() - holdback);
        int suffixStart = pending.length();
        while (suffixStart > 0 && normalize(pending.substring(suffixStart)).length() < need) {
            suffixStart--;
        }
        return Math.max(0, Math.min(rawSafe, suffixStart));
    }

    /**
     * 在归一后的文本上匹配关键词
     */
    private boolean hit(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return false;
        }
        return keywords.stream().anyMatch(normalized::contains);
    }

    /**
     * 文本归一：NFKC（全角转半角等）+ 去除所有空白
     */
    private static String normalize(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
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
