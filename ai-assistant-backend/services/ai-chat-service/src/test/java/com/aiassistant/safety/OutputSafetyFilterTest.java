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

    @Test
    void emergencyHitsWhenRedlineKeywordPresent() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of("胸痛", "昏迷"));
        assertTrue(new OutputSafetyFilter(props).emergencyHit("突然胸痛伴大汗"));
    }

    @Test
    void emergencyPassesWhenNoHit() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of("胸痛"));
        assertFalse(new OutputSafetyFilter(props).emergencyHit("眼睛干涩想挂眼科"));
    }

    @Test
    void emergencyPassesWhenListEmptyOrTextNull() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of());
        OutputSafetyFilter filter = new OutputSafetyFilter(props);
        assertFalse(filter.emergencyHit("胸痛"));
        assertFalse(filter.emergencyHit(null));
    }

    @Test
    void emergencyDefaultListWorksWithoutConfig() {
        assertTrue(new OutputSafetyFilter(new AiProperties()).emergencyHit("突发胸痛"));
    }

    @Test
    void emergencyMessageFromConfig() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyMessage("紧急就医指引");
        assertEquals("紧急就医指引", new OutputSafetyFilter(props).emergencyMessage());
    }

    @Test
    void emergencyIgnoresNegatedSymptom() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of("胸痛"));
        assertFalse(new OutputSafetyFilter(props).emergencyHit("我没有胸痛，只是有点咳嗽"));
    }

    @Test
    void emergencyHitsPlainSymptom() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of("胸痛"));
        assertTrue(new OutputSafetyFilter(props).emergencyHit("胸痛"));
    }

    @Test
    void emergencyIgnoresNegationVariants() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of("胸痛"));
        OutputSafetyFilter filter = new OutputSafetyFilter(props);
        assertFalse(filter.emergencyHit("不胸痛"));
        assertFalse(filter.emergencyHit("无胸痛"));
        assertFalse(filter.emergencyHit("未胸痛"));
        assertFalse(filter.emergencyHit("否认胸痛"));
        assertFalse(filter.emergencyHit("不伴胸痛"));
        assertFalse(filter.emergencyHit("没有胸痛"));
    }

    @Test
    void emergencyStillHitsWhenNegationIsFarAway() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of("胸痛"));
        assertTrue(new OutputSafetyFilter(props).emergencyHit("不发烧，但突然胸痛伴大汗"));
    }

    @Test
    void emergencyNormalizesSpaces() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of("胸痛"));
        assertTrue(new OutputSafetyFilter(props).emergencyHit("胸 痛 伴 大 汗"));
    }

    @Test
    void emergencyNormalizesFullWidthCharacters() {
        AiProperties props = new AiProperties();
        props.getSafety().setEmergencyKeywords(List.of("胸痛"));
        // 全角字形 + 全角空格（U+3000）
        assertTrue(new OutputSafetyFilter(props).emergencyHit("胸　痛"));
    }

    @Test
    void emergencyDefaultListContainsColloquialVariants() {
        OutputSafetyFilter filter = new OutputSafetyFilter(new AiProperties());
        assertTrue(filter.emergencyHit("胸口疼得厉害"));
        assertTrue(filter.emergencyHit("怀疑心梗"));
        assertTrue(filter.emergencyHit("感觉窒息"));
        assertTrue(filter.emergencyHit("喘不过气"));
    }

    @Test
    void blockedInputNormalizesSpaces() {
        AiProperties props = new AiProperties();
        props.getSafety().setKeywords(List.of("开药"));
        OutputSafetyFilter filter = new OutputSafetyFilter(props);
        assertTrue(filter.blockedInput("帮 我 开 药"));
        assertTrue(filter.blockedInput("帮　我　开　药"));
    }

    @Test
    void blockedInputKeepsNegatedKeywordBlocked() {
        // 输入侧为敏感句式，否定词不豁免
        assertTrue(filterWith(List.of("开药")).blockedInput("不要开药，只想知道挂什么科"));
    }
}
