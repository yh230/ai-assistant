package com.aiassistant.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;
import java.util.Map;

/**
 * 知识库切片器：按 token 窗口切分为向量化片段，metadata 保留来源文件名
 *
 * @author yh
 * @date 2026-10-13
 */
@RequiredArgsConstructor
public class KnowledgeChunker {

    /** 切片 token 上限（Nacos ai.rag.chunk-size 下发） */
    private final int chunkSize;

    /**
     * 将文档文本切分为切片
     *
     * @param sourceName 来源文件名（写入 metadata.source，供删除重建时溯源）
     * @param text 文档全文
     * @return 切片列表
     */
    public List<Document> split(String sourceName, String text) {
        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, 350, 5, 10000, true);
        List<Document> documents = splitter.apply(List.of(new Document(text)));
        return documents.stream()
                .map(d -> new Document(d.getText(), Map.of("source", sourceName)))
                .toList();
    }
}
