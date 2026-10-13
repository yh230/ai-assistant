package com.aiassistant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档（按医院隔离；content 为原文全文，供向量重建）
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属医院 ID（数据隔离边界） */
    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    /** 文件名 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 文件类型（md/txt） */
    @Column(length = 10)
    private String type;

    /** 文件大小（字节） */
    private Long size;

    /** 切片数量 */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** 文档全文（删除重建向量用） */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 上传人（员工用户 ID） */
    @Column(name = "create_by")
    private Long createBy;

    /** 上传时间 */
    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }
}
