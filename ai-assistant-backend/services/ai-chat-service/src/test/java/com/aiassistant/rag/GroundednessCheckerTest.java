package com.aiassistant.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * groundedness 启发式校验测试
 *
 * @author yh
 * @date 2026-10-19
 */
class GroundednessCheckerTest {

    private static final double THRESHOLD = 0.35;

    private final GroundednessChecker checker = new GroundednessChecker();

    private KnowledgeStore.ScoredChunk hit(String content) {
        return new KnowledgeStore.ScoredChunk("a.md", 1L, 0, "门诊", content, 0.9);
    }

    @Test
    void groundedWhenAnswerMostlyCoveredByHits() {
        String answer = "门诊时间为周一至周五上午八点到下午五点";

        assertTrue(checker.isGrounded(answer,
                List.of(hit("门诊时间为周一至周五8:00-17:00，节假日除外。")), THRESHOLD));
    }

    @Test
    void notGroundedWhenAnswerUnrelated() {
        String answer = "今天天气晴朗适合外出散步";

        assertFalse(checker.isGrounded(answer,
                List.of(hit("门诊时间为周一至周五8:00-17:00。")), THRESHOLD));
    }

    @Test
    void respectsThreshold() {
        String answer = "门诊时间为周一至周五上午八点到下午五点";

        assertFalse(checker.isGrounded(answer,
                List.of(hit("门诊时间为周一至周五8:00-17:00，节假日除外。")), 0.9));
    }

    @Test
    void matchesAsciiWordsCaseInsensitively() {
        assertTrue(checker.isGrounded("MRI Appointment",
                List.of(hit("mri appointment available on weekdays")), THRESHOLD));
    }

    @Test
    void trueWhenNoHitsOrBlankAnswer() {
        assertTrue(checker.isGrounded("任意回答", List.of(), THRESHOLD));
        assertTrue(checker.isGrounded("任意回答", null, THRESHOLD));
        assertTrue(checker.isGrounded("   ", List.of(hit("门诊时间")), THRESHOLD));
        assertTrue(checker.isGrounded(null, List.of(hit("门诊时间")), THRESHOLD));
    }

    @Test
    void partialCoverageNearThresholdDecidesBothSides() {
        // 20 个 ASCII 词，命中 7 个 → 覆盖率恰为 0.35
        String answer = "alpha bravo charlie delta echo foxtrot golf hotel india juliet "
                + "kilo lima mike november oscar papa quebec romeo sierra tango";
        KnowledgeStore.ScoredChunk covered =
                hit("alpha bravo charlie delta echo foxtrot golf");

        assertTrue(checker.isGrounded(answer, List.of(covered), 0.34));
        assertFalse(checker.isGrounded(answer, List.of(covered), 0.36));
    }

    @Test
    void clampsThresholdIntoUnitInterval() {
        KnowledgeStore.ScoredChunk covered = hit("门诊时间");

        // 负阈值收敛为 0
        assertTrue(checker.isGrounded("门诊时间", List.of(covered), -3.0));
        // 超过 1 的阈值收敛为 1：全覆盖为 true，部分覆盖为 false
        assertTrue(checker.isGrounded("门诊时间", List.of(covered), 5.0));
        assertFalse(checker.isGrounded("今天天气", List.of(covered), 5.0));
    }

    @Test
    void trueWhenAnswerHasNoComparableFeatures() {
        assertTrue(checker.isGrounded("。。。", List.of(hit("门诊时间")), THRESHOLD));
    }
}
