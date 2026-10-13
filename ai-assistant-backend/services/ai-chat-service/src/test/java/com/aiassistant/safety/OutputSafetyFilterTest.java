package com.aiassistant.safety;

import com.aiassistant.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 输入输出风控过滤器单元测试（关键词命中判定四场景）
 *
 * @author yh
 * @date 2026-10-13
 */
class OutputSafetyFilterTest {

    private OutputSafetyFilter filterWith(List<String> keywords) {
        AiProperties props = new AiProperties();
        props.getSafety().setKeywords(keywords);
        return new OutputSafetyFilter(props);
    }

    @Test
    void blocksWhenKeywordHit() {
        assertTrue(filterWith(List.of("开药", "确诊")).blockedInput("帮我开药治疗失眠"));
    }

    @Test
    void passesWhenNoHit() {
        assertFalse(filterWith(List.of("开药", "确诊")).blockedInput("我头疼应该挂哪个科"));
    }

    @Test
    void passesWhenKeywordListEmpty() {
        assertFalse(filterWith(List.of()).blockedInput("给我开药"));
    }

    @Test
    void passesWhenTextNull() {
        assertFalse(filterWith(List.of("开药")).blockedInput(null));
    }
}
