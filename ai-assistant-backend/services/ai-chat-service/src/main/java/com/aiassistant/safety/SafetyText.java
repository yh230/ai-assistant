package com.aiassistant.safety;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 风控文本归一与拒绝语境判断：输入/输出过滤与流式守卫共用。
 *
 * @author yh
 * @date 2026-10-27
 */
final class SafetyText {

    /** 输出侧拒绝前缀（命中后视为模型在拒答，不拦截关键词） */
    private static final List<String> REFUSAL_PREFIXES = List.of(
            "不能", "无法", "不可以", "不建议", "禁止", "避免", "不要", "勿");

    /** 拒绝前缀扫描窗口（归一后字符数） */
    private static final int REFUSAL_WINDOW = 6;

    /** 分句标点：拒绝语境不跨分句 */
    private static final String CLAUSE_DELIMITERS = "，。；：！？、,.!?;:\n\r";

    /** 空白（含全角空格 U+3000） */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u3000]+");

    private SafetyText() {
    }

    /**
     * 文本归一：NFKC（全角转半角等）+ 去除所有空白，防止空格/全角拆分绕过关键词。
     *
     * @param text 原始文本
     * @return 归一后文本
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
    }

    /**
     * 判断关键词出现位置前是否为拒绝语境（同一分句、窗口内的拒绝前缀）。
     *
     * @param text  归一后文本
     * @param index 关键词起始下标
     * @return true=属于拒绝语境
     */
    static boolean refusalPrefixed(String text, int index) {
        String prefix = text.substring(Math.max(0, index - REFUSAL_WINDOW), index);
        int delimiter = -1;
        for (int i = prefix.length() - 1; i >= 0; i--) {
            if (CLAUSE_DELIMITERS.indexOf(prefix.charAt(i)) >= 0) {
                delimiter = i;
                break;
            }
        }
        if (delimiter >= 0) {
            prefix = prefix.substring(delimiter + 1);
        }
        return REFUSAL_PREFIXES.stream().anyMatch(prefix::contains);
    }
}
