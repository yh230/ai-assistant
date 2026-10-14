package com.aiassistant.controller;

import com.aiassistant.dto.KnowledgeDocumentDTO;
import com.aiassistant.service.KnowledgeService;
import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.operlog.OperLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端知识库文档接口（上传/列表/删除，均限定本院数据）
 *
 * @author yh
 * @date 2026-10-13
 */
@RestController
@RequestMapping("/api/chat/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 上传文档（md/txt ≤2MB），完成后自动切片并向量化
     *
     * @param file 上传文件
     * @return 新文档 ID
     */
    @OperLog(module = "知识库", action = "上传文档")
    @PostMapping("/upload")
    public Result<Long> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(knowledgeService.upload(file));
    }

    /**
     * 文档分页（仅本院）
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页大小
     * @return 文档分页数据
     */
    @GetMapping
    public Result<PageResult<KnowledgeDocumentDTO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                         @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(knowledgeService.page(pageNum, pageSize));
    }

    /**
     * 删除文档（同步清理向量分片）
     *
     * @param id 文档 ID
     * @return 空结果
     */
    @OperLog(module = "知识库", action = "删除文档")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return Result.success();
    }
}
