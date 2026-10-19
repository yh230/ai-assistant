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

    /** 状态：处理中 */
    public static final int STATUS_PENDING = 0;

    /** 状态：就绪 */
    public static final int STATUS_READY = 1;

    /** 状态：处理失败（重试耗尽转入死信） */
    public static final int STATUS_FAILED = 2;

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

    /** 处理状态：0-处理中（待切片向量化），1-就绪，2-处理失败 */
    @Column(nullable = false)
    private Integer status = STATUS_PENDING;

    /** 处理失败原因（供管理端提示用户） */
    @Column(name = "error_msg", length = 500)
    private String errorMsg;

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
