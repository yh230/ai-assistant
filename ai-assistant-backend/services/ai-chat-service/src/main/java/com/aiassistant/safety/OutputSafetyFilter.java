package com.aiassistant.safety;

import com.aiassistant.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AI 输入输出风控过滤器（关键词规则）：命中开药/确诊类敏感词即拦截（输入抛错/输出改文案）；急症红线词命中由服务层短路返回就医指引
 * <p>
 * 输入侧命中：不调用大模型，直接返回标准提示文案；输出侧命中：丢弃生成内容，持久化标准文案。
 * 文本统一经 NFKC + 去空白归一后再比对，避免全角字符/空格拆分绕过；急症词命中若紧邻否定词（如"没有胸痛"）则视为否定描述而不拦截。
 *
 * @author yh
 * @date 2026-10-13
 */
@Component
@RequiredArgsConstructor
public class OutputSafetyFilter {

    /** 急症词前置否定词（命中位置前 3 个字符内出现即视为否定描述） */
    private static final List<String> NEGATIONS = List.of("不", "没", "无", "未", "否认", "没有", "不伴");

    /** 文本归一：NFKC（全角转半角）+ 去除所有空白 */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u3000]+");

    private final AiProperties aiProperties;

    /**
     * 判断文本是否命中敏感词
     *
     * @param text 待检查文本
     * @return true=命中（应拦截）
     */
    public boolean blockedInput(String text) {
        if (text == null || aiProperties.getSafety().getKeywords() == null) {
            return false;
        }
        String normalized = normalize(text);
        return aiProperties.getSafety().getKeywords().stream()
                .filter(StringUtils::hasText)
                .map(OutputSafetyFilter::normalize)
                .anyMatch(normalized::contains);
    }

    /**
     * 获取命中后的标准提示文案
     *
     * @return 标准提示文案
     */
    public String blockedMessage() {
        return aiProperties.getSafety().getBlockedMessage();
    }

    /**
     * 判断文本是否命中急症红线词（归一后扫描，否定描述不计入命中）
     *
     * @param text 待检查文本
     * @return true=命中（应短路返回紧急就医指引）
     */
    public boolean emergencyHit(String text) {
        List<String> words = aiProperties.getSafety().getEmergencyKeywords();
        if (text == null || words == null) {
            return false;
        }
        String normalized = normalize(text);
        for (String word : words) {
            if (!StringUtils.hasText(word)) {
                continue;
            }
            String keyword = normalize(word);
            if (keyword.isEmpty()) {
                continue;
            }
            int index = normalized.indexOf(keyword);
            while (index >= 0) {
                if (!negated(normalized, index)) {
                    return true;
                }
                index = normalized.indexOf(keyword, index + keyword.length());
            }
        }
        return false;
    }

    /**
     * 获取急症红线标准指引文案
     *
     * @return 紧急就医指引文案
     */
    public String emergencyMessage() {
        return aiProperties.getSafety().getEmergencyMessage();
    }

    /**
     * 文本归一：NFKC 兼容分解（全角转半角等）+ 去除空白，防止空格/全角拆分绕过关键词
     *
     * @param text 原始文本
     * @return 归一后文本
     */
    private static String normalize(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
    }

    /**
     * 判断命中位置前 3 个字符内是否出现否定词
     *
     * @param text  归一后文本
     * @param index 关键词起始下标
     * @return true=属于否定描述
     */
    private boolean negated(String text, int index) {
        String prefix = text.substring(Math.max(0, index - 3), index);
        return NEGATIONS.stream().anyMatch(prefix::contains);
    }
}
