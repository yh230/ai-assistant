package com.aiassistant.service.impl;

import com.aiassistant.config.AiProperties;
import com.aiassistant.dto.KnowledgeDocumentDTO;
import com.aiassistant.entity.KnowledgeDocument;
import com.aiassistant.rag.KnowledgeChunker;
import com.aiassistant.rag.KnowledgeVectorStore;
import com.aiassistant.repository.KnowledgeDocumentRepository;
import com.aiassistant.service.KnowledgeService;
import com.common.auth.UserSnapshot;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.common.security.SecurityUtils;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 知识库文档管理（上传/列表/删除；全部操作限定在操作者医院范围内）
 * <p>
 * 向量数据与文档行同步：上传即切片入向量；删除后按该医院剩余文档重建向量文件。
 *
 * @author yh
 * @date 2026-10-13
 */
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    /** 单文档大小上限（字节） */
    private static final long MAX_SIZE = 2 * 1024 * 1024L;

    /** 允许上传的文档扩展名 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "txt");

    /** 知识库文档仓库 */
    private final KnowledgeDocumentRepository documentRepository;

    /** 文档切片器 */
    private final KnowledgeChunker knowledgeChunker;

    /** 本院向量存储 */
    private final KnowledgeVectorStore knowledgeVectorStore;

    /**
     * 构造器注入：切片大小取 Nacos ai.rag.chunk-size（KnowledgeChunker 为普通类，非容器组件）
     *
     * @param documentRepository   知识库文档仓库
     * @param aiProperties         AI 配置（取切片大小）
     * @param knowledgeVectorStore 知识库向量存储
     */
    public KnowledgeServiceImpl(KnowledgeDocumentRepository documentRepository,
                                AiProperties aiProperties,
                                KnowledgeVectorStore knowledgeVectorStore) {
        this.documentRepository = documentRepository;
        this.knowledgeChunker = new KnowledgeChunker(aiProperties.getRag().getChunkSize());
        this.knowledgeVectorStore = knowledgeVectorStore;
    }

    @Override
    @Transactional
    public Long upload(MultipartFile file) {
        UserSnapshot user = currentStaffWithHospital();
        Long hospitalId = user.getHospitalId();
        String filename = file.getOriginalFilename();
        String ext = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("仅支持 md/txt 格式文档");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("文档大小不能超过 2MB");
        }
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("文档读取失败");
        }
        List<Document> chunks = knowledgeChunker.split(filename, text);
        knowledgeVectorStore.addDocuments(hospitalId, chunks);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setHospitalId(hospitalId);
        doc.setName(filename);
        doc.setType(ext);
        doc.setSize(file.getSize());
        doc.setChunkCount(chunks.size());
        doc.setContent(text);
        doc.setCreateBy(user.getUserId());
        return documentRepository.save(doc).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<KnowledgeDocumentDTO> page(long pageNum, long pageSize) {
        UserSnapshot user = currentStaffWithHospital();
        Page<KnowledgeDocument> page = documentRepository.findByHospitalIdOrderByCreateTimeDesc(
                user.getHospitalId(), PageRequest.of((int) pageNum - 1, (int) pageSize));
        List<KnowledgeDocumentDTO> rows = page.getContent().stream()
                .map(d -> KnowledgeDocumentDTO.builder()
                        .id(d.getId()).name(d.getName()).type(d.getType()).size(d.getSize())
                        .chunkCount(d.getChunkCount()).createTime(d.getCreateTime()).build())
                .toList();
        PageResult<KnowledgeDocumentDTO> result = new PageResult<>();
        result.setTotal(page.getTotalElements());
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setList(rows);
        return result;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        UserSnapshot user = currentStaffWithHospital();
        KnowledgeDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException("文档不存在"));
        if (!user.getHospitalId().equals(doc.getHospitalId())) {
            throw new BusinessException("无权操作该文档");
        }
        documentRepository.delete(doc);
        // 按该医院剩余文档重建向量（文档量小，重建简单可靠）
        knowledgeVectorStore.rebuild(user.getHospitalId(), documentRepository.findByHospitalId(user.getHospitalId()));
    }

    /**
     * 取当前员工快照，医院未绑定则拒绝知识库操作
     *
     * @return 已绑定医院的当前用户快照
     */
    private UserSnapshot currentStaffWithHospital() {
        UserSnapshot user = SecurityUtils.currentSnapshot();
        if (user == null || user.getHospitalId() == null) {
            throw new BusinessException("当前账号未绑定医院，无法管理知识库");
        }
        return user;
    }

    /**
     * 取文件名扩展名（小写）
     *
     * @param filename 原始文件名
     * @return 扩展名；无扩展名返回空串
     */
    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
