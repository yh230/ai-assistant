package com.aiassistant.repository;

import com.aiassistant.entity.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 知识库文档 Repository
 *
 * @author yh
 * @date 2026-10-13
 */
@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    /**
     * 按医院分页查询文档（上传时间倒序）
     *
     * @param hospitalId 医院 ID
     * @param pageable   分页参数
     * @return 文档分页结果
     */
    Page<KnowledgeDocument> findByHospitalIdOrderByCreateTimeDesc(Long hospitalId, Pageable pageable);

    /**
     * 查询医院全部文档（删除后重建向量的数据源）
     *
     * @param hospitalId 医院 ID
     * @return 文档列表；无数据返回空列表
     */
    List<KnowledgeDocument> findByHospitalId(Long hospitalId);

    /**
     * 查询医院指定状态的文档
     *
     * @param hospitalId 医院 ID
     * @param status     文档状态
     * @return 符合条件的文档列表；无数据返回空列表
     */
    List<KnowledgeDocument> findByHospitalIdAndStatus(Long hospitalId, Integer status);
}
