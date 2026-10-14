package com.aiassistant.service.impl;

import com.aiassistant.dto.AdminSessionDTO;
import com.aiassistant.dto.ChatResponse;
import com.aiassistant.entity.ChatSession;
import com.aiassistant.repository.ChatMessageRepository;
import com.aiassistant.repository.ChatSessionRepository;
import com.aiassistant.service.AdminChatService;
import com.api.client.PatientClient;
import com.api.dto.UserBriefDTO;
import com.common.auth.UserSnapshot;
import com.common.constant.SecurityConstants;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理端会话查询（仅患者会话，按操作者医院范围做数据隔离）
 * <p>
 * 数据范围口径：快照 hospitalId 非空=仅本院数据；为空（平台管理员）不额外过滤。
 *
 * @author yh
 * @date 2026-10-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminChatServiceImpl implements AdminChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final PatientClient patientClient;

    @Override
    public PageResult<AdminSessionDTO> pageSessions(String sessionType, long pageNum, long pageSize) {
        UserSnapshot user = SecurityUtils.currentSnapshot();
        if (user == null) {
            throw new BusinessException("未登录");
        }
        Specification<ChatSession> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("identity"), SecurityConstants.IDENTITY_PATIENT));
            if (user.getHospitalId() != null) {
                predicates.add(cb.equal(root.get("hospitalId"), user.getHospitalId()));
            }
            if (StringUtils.hasText(sessionType)) {
                predicates.add(cb.equal(root.get("sessionType"), sessionType));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<ChatSession> page = sessionRepository.findAll(spec,
                PageRequest.of((int) pageNum - 1, (int) pageSize, Sort.by(Sort.Direction.DESC, "updatedAt")));
        Map<Long, UserBriefDTO> patients = batchPatients(page.getContent());
        List<AdminSessionDTO> rows = page.getContent().stream().map(s -> {
            UserBriefDTO p = patients.get(s.getUserId());
            return AdminSessionDTO.builder()
                    .sessionId(s.getSessionId()).title(s.getTitle()).sessionType(s.getSessionType())
                    .userId(s.getUserId())
                    .patientName(p == null ? null : p.getName())
                    .patientPhone(p == null ? null : p.getPhone())
                    .hospitalId(s.getHospitalId())
                    .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt()).build();
        }).toList();
        PageResult<AdminSessionDTO> result = new PageResult<>();
        result.setTotal(page.getTotalElements());
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setList(rows);
        return result;
    }

    /**
     * 批量回填患者姓名/手机号（患者中心不可用时不阻断列表）
     *
     * @param sessions 当前页会话列表
     * @return 患者 ID → 简要信息（查询失败返回空 Map）
     */
    private Map<Long, UserBriefDTO> batchPatients(List<ChatSession> sessions) {
        List<Long> ids = sessions.stream().map(ChatSession::getUserId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Result<List<UserBriefDTO>> result = patientClient.batch(ids);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                return result.getData().stream().collect(Collectors.toMap(UserBriefDTO::getId, u -> u, (a, b) -> a));
            }
        } catch (Exception e) {
            log.warn("回填患者信息失败: {}", e.getMessage());
        }
        return Map.of();
    }

    @Override
    public ChatResponse getSessionHistory(String sessionId) {
        UserSnapshot user = SecurityUtils.currentSnapshot();
        ChatSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException("会话不存在"));
        if (!SecurityConstants.IDENTITY_PATIENT.equals(session.getIdentity())) {
            throw new BusinessException("无权查看该会话");
        }
        // 口径与列表一致：平台管理员（未绑定医院）放行；本院管理员仅可查看本院（hospitalId 非空且相等）会话
        if (user == null
                || (user.getHospitalId() != null
                && (session.getHospitalId() == null || !session.getHospitalId().equals(user.getHospitalId())))) {
            throw new BusinessException("无权查看该会话");
        }
        List<ChatResponse.ChatMessageDTO> messageDTOs =
                messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                        .map(m -> ChatResponse.ChatMessageDTO.builder()
                                .role(m.getRole()).content(m.getContent()).createdAt(m.getCreatedAt()).build())
                        .collect(Collectors.toList());
        return ChatResponse.builder()
                .sessionId(sessionId).messages(messageDTOs)
                .timestamp(LocalDateTime.now()).build();
    }
}
