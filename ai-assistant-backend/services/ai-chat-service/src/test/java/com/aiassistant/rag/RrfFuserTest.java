package com.aiassistant.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RrfFuser 单元测试：融合排序、同片段去重、topK 截断、同分稳定顺序与边界入参
 *
 * @author yh
 * @date 2026-10-19
 */
class RrfFuserTest {

    /** 构造片段 */
    private static KnowledgeStore.ScoredChunk chunk(String source, long documentId, int chunkIndex, double score) {
        return new KnowledgeStore.ScoredChunk(source, documentId, chunkIndex, "s", "content-" + source, score);
    }

    @Test
    void fusesByReciprocalRank() {
        var a = chunk("a.md", 1L, 0, 0.9);
        var b = chunk("b.md", 2L, 0, 0.8);
        var fused = RrfFuser.fuse(List.of(a, b), List.of(b), 2, 60);
        assertEquals(2, fused.size());
        assertEquals("b.md", fused.get(0).source());
        assertEquals(1.0 / 61 + 1.0 / 61, fused.get(0).score(), 1e-9);
        assertEquals(1.0 / 61, fused.get(1).score(), 1e-9);
    }

    @Test
    void dedupesSameChunkAcrossLists() {
        var a = chunk("a.md", 1L, 3, 0.9);
        var fused = RrfFuser.fuse(List.of(a), List.of(a), 10, 60);
        assertEquals(1, fused.size());
        assertEquals(1L, fused.get(0).documentId().longValue());
        assertEquals(3, fused.get(0).chunkIndex());
        assertEquals(2.0 / 61, fused.get(0).score(), 1e-9);
    }

    @Test
    void ignoresDuplicateEntriesWithinOneList() {
        var a = chunk("a.md", 1L, 0, 0.9);
        var fused = RrfFuser.fuse(List.of(a, a), List.of(), 10, 60);
        assertEquals(1, fused.size());
        assertEquals(1.0 / 61, fused.get(0).score(), 1e-9);
    }

    @Test
    void limitsToTopK() {
        var a = chunk("a.md", 1L, 0, 0.9);
        var b = chunk("b.md", 2L, 0, 0.8);
        var c = chunk("c.md", 3L, 0, 0.7);
        var fused = RrfFuser.fuse(List.of(a, b, c), List.of(), 2, 60);
        assertEquals(2, fused.size());
        assertEquals("a.md", fused.get(0).source());
        assertEquals("b.md", fused.get(1).source());
    }

    @Test
    void keepsFirstSeenOrderOnTie() {
        var a = chunk("a.md", 1L, 0, 0.9);
        var b = chunk("b.md", 2L, 0, 0.8);
        var fused = RrfFuser.fuse(List.of(a, b), List.of(), 5, 60);
        assertEquals("a.md", fused.get(0).source());
        assertEquals("b.md", fused.get(1).source());
    }

    @Test
    void handlesEmptyAndNonPositiveInput() {
        var a = chunk("a.md", 1L, 0, 0.9);
        assertTrue(RrfFuser.fuse(List.of(), List.of(), 5, 60).isEmpty());
        assertTrue(RrfFuser.fuse(null, null, 5, 60).isEmpty());
        assertTrue(RrfFuser.fuse(List.of(a), List.of(), 0, 60).isEmpty());
        assertEquals(1, RrfFuser.fuse(List.of(a), List.of(), 1, 0).size());
        assertEquals(1.0, RrfFuser.fuse(List.of(a), List.of(), 1, -5).get(0).score(), 1e-9);
    }
}
