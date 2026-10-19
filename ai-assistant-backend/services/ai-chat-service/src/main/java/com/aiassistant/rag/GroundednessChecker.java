package com.aiassistant.rag;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * groundedness 启发式校验：以 CJK 二元组与 ASCII 单词为特征，衡量回答内容被检索片段
 * 覆盖的比例，低于阈值视为可能脱离知识库依据。
 *
 * @author yh
 * @date 2026-10-19
 */
@Component
public class GroundednessChecker {

    /** ASCII 单词（字母/数字）提取 */
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+");

    /** 过短 ASCII 单词（单字符）信息量低，不计入覆盖率 */
    private static final int MIN_ASCII_WORD_LENGTH = 2;

    /**
     * 判断回答是否由检索片段支撑
     *
     * @param answer    模型回答
     * @param hits      检索命中片段
     * @param threshold 覆盖率阈值（自动收敛到 [0,1]）
     * @return 覆盖率达到阈值返回 true；无命中、空回答或无有效特征时视为 true
     */
    public boolean isGrounded(String answer, List<KnowledgeStore.ScoredChunk> hits, double threshold) {
        if (hits == null || hits.isEmpty() || answer == null || answer.isBlank()) {
            return true;
        }
        double effectiveThreshold = Math.min(1.0, Math.max(0.0, threshold));
        Set<String> answerGrams = grams(answer);
        if (answerGrams.isEmpty()) {
            return true;
        }
        Set<String> hitGrams = new HashSet<>();
        for (KnowledgeStore.ScoredChunk hit : hits) {
            if (hit == null || hit.content() == null) {
                continue;
            }
            hitGrams.addAll(grams(hit.content()));
        }
        long matched = answerGrams.stream().filter(hitGrams::contains).count();
        return (double) matched / answerGrams.size() >= effectiveThreshold;
    }

    /**
     * 提取文本特征：连续 CJK 片段取二元组（单字片段取单字），ASCII 单词转小写后取长度≥2 的词
     *
     * @param text 文本
     * @return 特征集合
     */
    private Set<String> grams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return grams;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                cjkRun.append(c);
            } else {
                addCjkGrams(grams, cjkRun);
                cjkRun.setLength(0);
            }
        }
        addCjkGrams(grams, cjkRun);
        Matcher matcher = ASCII_WORD.matcher(lower);
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() >= MIN_ASCII_WORD_LENGTH) {
                grams.add(word);
            }
        }
        return grams;
    }

    /**
     * 将连续 CJK 片段展开为二元组
     *
     * @param grams  特征集合
     * @param cjkRun CJK 连续片段
     */
    private void addCjkGrams(Set<String> grams, StringBuilder cjkRun) {
        int length = cjkRun.length();
        if (length == 1) {
            grams.add(cjkRun.toString());
        } else if (length > 1) {
            for (int i = 0; i + 1 < length; i++) {
                grams.add(cjkRun.substring(i, i + 2));
            }
        }
    }

    /**
     * 判断是否为 CJK 统一表意文字
     *
     * @param c 字符
     * @return 是 CJK 返回 true
     */
    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
