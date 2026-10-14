package com.aiassistant.safety;

import com.aiassistant.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AI 输入输出风控过滤器（关键词规则）：命中开药/确诊类敏感词即拦截
 * <p>
 * 输入侧命中：不调用大模型，直接返回标准提示文案；输出侧命中：丢弃生成内容，持久化标准文案。
 *
 * @author yh
 * @date 2026-10-13
 */
@Component
@RequiredArgsConstructor
public class OutputSafetyFilter {

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
        return aiProperties.getSafety().getKeywords().stream()
                .filter(StringUtils::hasText)
                .anyMatch(text::contains);
    }

    /**
     * 获取命中后的标准提示文案
     *
     * @return 标准提示文案
     */
    public String blockedMessage() {
        return aiProperties.getSafety().getBlockedMessage();
    }
}
