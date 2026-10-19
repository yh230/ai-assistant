package com.aiassistant.service.impl;

import com.aiassistant.dto.KnowledgeDocumentDTO;
import com.aiassistant.entity.KnowledgeDocument;
import com.aiassistant.mq.KnowledgeTaskPublisher;
import com.aiassistant.rag.KnowledgeContentGuard;
import com.aiassistant.rag.KnowledgeVectorStore;
import com.aiassistant.repository.KnowledgeDocumentRepository;
import com.aiassistant.service.KnowledgeService;
import com.common.auth.UserSnapshot;
import com.common.constant.SecurityConstants;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.common.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 知识库文档管理（上传/列表/删除；全部操作限定在操作者医院范围内）
 * <p>
 * 上传落库即返回，切片向量化经 Kafka 异步完成；删除后按该医院已就绪文档重建向量文件。
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

    /** 解码后允许的最大 U+FFFD 替换符数量 */
    private static final int MAX_REPLACEMENT_CHARS = 10;

    /** 解码后允许的最大 U+FFFD 替换符占比 */
    private static final double MAX_REPLACEMENT_RATIO = 0.01;

    /** 知识库文档仓库 */
    private final KnowledgeDocumentRepository documentRepository;

    /** 本院向量存储 */
    private final KnowledgeVectorStore knowledgeVectorStore;

    /** 知识库任务事件发布器 */
    private final KnowledgeTaskPublisher taskPublisher;

    /** 知识库内容注入防护 */
    private final KnowledgeContentGuard knowledgeContentGuard;

    /**
     * 构造器注入
     *
     * @param documentRepository    知识库文档仓库
     * @param knowledgeVectorStore  知识库向量存储
     * @param taskPublisher         知识库任务事件发布器
     * @param knowledgeContentGuard 知识库内容注入防护
     */
    public KnowledgeServiceImpl(KnowledgeDocumentRepository documentRepository,
                                KnowledgeVectorStore knowledgeVectorStore,
                                KnowledgeTaskPublisher taskPublisher,
                                KnowledgeContentGuard knowledgeContentGuard) {
        this.documentRepository = documentRepository;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.taskPublisher = taskPublisher;
        this.knowledgeContentGuard = knowledgeContentGuard;
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
            text = decode(file.getBytes());
        } catch (IOException e) {
            throw new BusinessException("文档读取失败");
        }
        // 知识文档会被检索进对话上下文，上传时即拒绝疑似提示词注入内容
        knowledgeContentGuard.assertSafe(text);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setHospitalId(hospitalId);
        doc.setName(filename);
        doc.setType(ext);
        doc.setSize(file.getSize());
        doc.setChunkCount(0);
        doc.setContent(text);
        doc.setCreateBy(user.getUserId());
        KnowledgeDocument saved = documentRepository.save(doc);
        // 落库即返回，切片向量化交由消费者异步完成；事件须在事务提交后发送，避免消费者读到未提交数据
        Runnable publish = () -> taskPublisher.publishUploaded(saved.getId(), hospitalId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
        return saved.getId();
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
                        .chunkCount(d.getChunkCount()).status(d.getStatus()).createTime(d.getCreateTime()).build())
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
        // 按该医院已就绪文档重建向量（未就绪文档尚未入向量；文档量小，重建简单可靠）
        knowledgeVectorStore.rebuild(user.getHospitalId(),
                documentRepository.findByHospitalIdAndStatus(user.getHospitalId(), KnowledgeDocument.STATUS_READY));
    }

    /**
     * 取当前员工快照，非员工身份或医院未绑定则拒绝知识库操作
     *
     * @return 已绑定医院的当前用户快照
     */
    private UserSnapshot currentStaffWithHospital() {
        UserSnapshot user = SecurityUtils.currentSnapshot();
        if (user == null || !SecurityConstants.IDENTITY_STAFF.equals(user.getIdentity())) {
            throw new BusinessException("无权访问管理端接口");
        }
        if (user.getHospitalId() == null) {
            throw new BusinessException("当前账号未绑定医院，无法管理知识库");
        }
        return user;
    }

    /**
     * 解码文档字节：剥离 UTF-8 BOM，非法 UTF-8 回退 GB18030（兼容 Windows 下保存的中文 txt）
     *
     * @param bytes 原始字节
     * @return 文档文本
     * @throws BusinessException 解码结果含过多替换符（无法识别的编码）
     */
    private String decode(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return checkDecoded(new String(bytes, Charset.forName("GB18030")));
        }
        return checkDecoded(text);
    }

    /**
     * 校验解码文本：替换符过多说明编码无法识别，拒绝入库以免把乱码喂给检索与模型
     *
     * @param text 解码后的文本
     * @return 原文本
     * @throws BusinessException 替换符数量或占比超限
     */
    private String checkDecoded(String text) {
        long replacements = text.chars().filter(c -> c == '\uFFFD').count();
        if (replacements > MAX_REPLACEMENT_CHARS
                || replacements > text.length() * MAX_REPLACEMENT_RATIO) {
            throw new BusinessException("文档编码无法识别，请使用 UTF-8 或 GB18030");
        }
        return text;
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
