package com.aiassistant.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 知识库结构切片器：先按 Markdown 标题切分章节、按空行切分段落，再按 token 上限打包为切片，
 * 相邻切片保留重叠尾部。切片 ID 形如 {@code documentId:index}，metadata 保留
 * source/documentId/chunkIndex/section，便于引用溯源与按 chunk 去重。
 *
 * @author yh
 * @date 2026-10-19
 */
public class KnowledgeChunker {

    /** 默认重叠比例（与 ai.rag.chunk-overlap 默认值保持一致） */
    private static final double DEFAULT_OVERLAP = 0.15;

    /** 重叠比例上限，避免切片无法向前推进 */
    private static final double MAX_OVERLAP = 0.5;

    /** 空行分隔的段落 */
    private static final Pattern BLANK_LINE = Pattern.compile("\\R\\s*\\R");

    /** Markdown 标题行（1~6 级） */
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");

    /** 切片 token 上限（Nacos ai.rag.chunk-size 下发） */
    private final int chunkSize;

    /** 相邻切片重叠比例（0~0.5，Nacos ai.rag.chunk-overlap 下发） */
    private final double overlap;

    /**
     * 使用默认重叠比例构造
     *
     * @param chunkSize 切片 token 上限
     */
    public KnowledgeChunker(int chunkSize) {
        this(chunkSize, DEFAULT_OVERLAP);
    }

    /**
     * 指定切片上限与重叠比例构造
     *
     * @param chunkSize 切片 token 上限
     * @param overlap   相邻切片重叠比例（0~0.5，超出范围自动收敛）
     */
    public KnowledgeChunker(int chunkSize, double overlap) {
        this.chunkSize = Math.max(1, chunkSize);
        this.overlap = Math.min(MAX_OVERLAP, Math.max(0.0, overlap));
    }

    /**
     * 将文档文本切分为切片（兼容旧调用）。
     * <p>
     * 旧签名没有真实文档 ID，这里为每次调用生成一个唯一且非负的合成 ID，避免不同文档的
     * 切片 ID（{@code documentId:index}）互相覆盖；同一文档多次切分得到的 ID 不同，
     * 因此该重载<b>不能</b>用于需要稳定 ID 的场景。
     * <p>
     * <b>兼容说明</b>：生产链路已全部改用 {@link #split(Long, String, String)} 并传入真实
     * documentId，本重载仅保留给兼容旧调用与测试使用。
     *
     * @param sourceName 来源文件名
     * @param text       文档全文
     * @return 切片列表
     * @deprecated 请改用 {@link #split(Long, String, String)}，旧方法缺少真实文档 ID，
     * 无法生成稳定的 chunk 级引用与去重键。
     */
    @Deprecated
    public List<Document> split(String sourceName, String text) {
        long syntheticDocumentId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return split(syntheticDocumentId, sourceName, text);
    }

    /**
     * 将文档文本结构感知地切分为切片
     *
     * @param documentId 文档 ID（写入切片 ID 前缀与 metadata，空值按 0 处理）
     * @param sourceName 来源文件名（写入 metadata.source，供删除重建时溯源）
     * @param text       文档全文
     * @return 切片列表；文本为空时返回空列表
     */
    public List<Document> split(Long documentId, String sourceName, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        long docId = documentId == null ? 0L : documentId;
        // 重叠 token 数上限为 chunkSize / 2，避免尾部重叠吞掉整片导致切片无法推进
        int overlapTokens = Math.min((int) Math.round(chunkSize * overlap), chunkSize / 2);
        List<Document> result = new ArrayList<>();
        int index = 0;
        for (Section section : sections(text)) {
            for (String chunk : pack(section.content(), chunkSize, overlapTokens)) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", sourceName == null ? "" : sourceName);
                metadata.put("documentId", docId);
                metadata.put("chunkIndex", index);
                metadata.put("section", section.title());
                result.add(Document.builder()
                        .id(docId + ":" + index)
                        .text(chunk)
                        .metadata(metadata)
                        .build());
                index++;
            }
        }
        return result;
    }

    /**
     * 按 Markdown 标题与空行解析章节：标题开启新章节，空行分隔章节内段落；
     * 无标题文档整体作为匿名章节，由打包阶段按空行切段。
     *
     * @param text 文档全文
     * @return 章节列表（空章节已过滤）
     */
    private List<Section> sections(String text) {
        List<Section> sections = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String title = "";
        for (String line : text.split("\\R", -1)) {
            if (HEADING.matcher(line).matches()) {
                addSection(sections, title, body);
                title = line.replaceFirst("^#{1,6}\\s+", "").trim();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        addSection(sections, title, body);
        return sections;
    }

    /**
     * 收集章节正文，空白章节丢弃
     *
     * @param sections 章节列表
     * @param title    章节标题
     * @param body     章节正文缓冲（收集后清空）
     */
    private void addSection(List<Section> sections, String title, StringBuilder body) {
        String content = body.toString().trim();
        if (!content.isEmpty()) {
            sections.add(new Section(title, content));
        }
        body.setLength(0);
    }

    /**
     * 按段落打包切片：段落加入后超过 token 上限则输出当前切片，并把尾部重叠文本
     * 带入下一片；单段超长时先按 token 上限硬切。
     *
     * @param content       章节正文
     * @param maxTokens     单切片 token 上限
     * @param overlapTokens 相邻切片重叠 token 数
     * @return 切片文本列表
     */
    private List<String> pack(String content, int maxTokens, int overlapTokens) {
        List<String> paragraphs = new ArrayList<>();
        for (String raw : BLANK_LINE.split(content)) {
            String paragraph = raw.trim();
            if (!paragraph.isEmpty()) {
                paragraphs.addAll(sliceOversized(paragraph, maxTokens));
            }
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        boolean overlapOnly = false;
        for (String paragraph : paragraphs) {
            int tokens = estimateTokens(paragraph);
            if (currentTokens > 0 && !overlapOnly && currentTokens + tokens > maxTokens) {
                String chunk = current.toString().trim();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                    String tail = tailOf(chunk, overlapTokens);
                    current.setLength(0);
                    currentTokens = 0;
                    if (!tail.isEmpty()) {
                        current.append(tail);
                        currentTokens = estimateTokens(tail);
                        overlapOnly = true;
                    }
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
            currentTokens += tokens;
            overlapOnly = false;
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            chunks.add(last);
        }
        return chunks;
    }

    /**
     * 超长段落按 token 上限硬切（中文按字、英文/数字按 4 字符 1 token 估算）
     *
     * @param paragraph 段落文本
     * @param maxTokens token 上限
     * @return 切分后的片段
     */
    private List<String> sliceOversized(String paragraph, int maxTokens) {
        if (estimateTokens(paragraph) <= maxTokens) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        int cjk = 0;
        int ascii = 0;
        for (int i = 0; i < paragraph.length(); i++) {
            char c = paragraph.charAt(i);
            piece.append(c);
            if (isCjk(c)) {
                cjk++;
            } else if (Character.isLetterOrDigit(c)) {
                ascii++;
            }
            if (cjk + Math.max(1, ascii / 4) >= maxTokens) {
                pieces.add(piece.toString());
                piece.setLength(0);
                cjk = 0;
                ascii = 0;
            }
        }
        if (!piece.toString().isBlank()) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    /**
     * 取切片末尾约 overlapTokens 个 token 的文本作为下一片前缀。
     * <p>
     * 若整个切片都不足 overlapTokens（尾部会等于上一片全文），返回空串而不是整片，
     * 保证重叠不会把上一片完整复制到下一片。
     *
     * @param text          已输出切片
     * @param overlapTokens 重叠 token 数
     * @return 尾部文本；无重叠或尾部将覆盖整片时返回空串
     */
    private String tailOf(String text, int overlapTokens) {
        if (overlapTokens <= 0 || text.isEmpty()) {
            return "";
        }
        int cjk = 0;
        int ascii = 0;
        int i = text.length() - 1;
        for (; i >= 0; i--) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else if (Character.isLetterOrDigit(c)) {
                ascii++;
            }
            if (cjk + ascii / 4 >= overlapTokens) {
                break;
            }
        }
        if (i <= 0) {
            return "";
        }
        return text.substring(i).trim();
    }

    /**
     * token 估算：CJK 字符按 1 token，ASCII 字母/数字按 4 字符 1 token（至少 1）
     *
     * @param text 文本
     * @return 估算 token 数
     */
    private int estimateTokens(String text) {
        int cjk = 0;
        int ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else if (Character.isLetterOrDigit(c)) {
                ascii++;
            }
        }
        return cjk + Math.max(1, ascii / 4);
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

    /**
     * 章节结构
     *
     * @param title   章节标题（无标题时为空串）
     * @param content 章节正文
     */
    private record Section(String title, String content) {
    }
}
