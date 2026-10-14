package com.aiassistant.service;

import com.aiassistant.dto.KnowledgeDocumentDTO;
import com.common.result.PageResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文档管理服务（全部操作限定在操作者医院范围内）
 *
 * @author yh
 * @date 2026-10-13
 */
public interface KnowledgeService {

    /**
     * 上传文档：校验格式与大小后切片入向量，并保存文档行（含全文 content）
     *
     * @param file 上传文件（md/txt，≤2MB）
     * @return 新文档 ID
     */
    Long upload(MultipartFile file);

    /**
     * 分页查询本院文档（上传时间倒序）
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页大小
     * @return 文档分页数据（不含全文）
     */
    PageResult<KnowledgeDocumentDTO> page(long pageNum, long pageSize);

    /**
     * 删除文档并按本院剩余文档重建向量文件
     *
     * @param id 文档 ID
     */
    void delete(Long id);
}
