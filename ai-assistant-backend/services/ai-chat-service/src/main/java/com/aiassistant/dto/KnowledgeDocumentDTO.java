package com.aiassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库文档列表项 DTO（不含全文 content）
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocumentDTO {

    /** 文档 ID */
    private Long id;

    /** 文件名 */
    private String name;

    /** 文件类型（md/txt） */
    private String type;

    /** 文件大小（字节） */
    private Long size;

    /** 切片数量 */
    private Integer chunkCount;

    /** 上传时间 */
    private LocalDateTime createTime;
}
