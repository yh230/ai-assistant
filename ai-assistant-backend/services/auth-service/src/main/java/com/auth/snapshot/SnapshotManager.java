package com.auth.snapshot;

import com.api.client.PatientClient;
import com.api.client.UserClient;
import com.api.dto.AuthInfoDTO;
import com.common.auth.UserSnapshot;
import com.common.auth.UserSnapshotStore;
import com.common.constant.SecurityConstants;
import com.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 权限快照管理：组装（员工调用户中心、患者调患者中心）/失效（auth 为快照唯一属主）
 *
 * @author yh
 * @date 2026-10-12
 */
@Component
@RequiredArgsConstructor
public class SnapshotManager {

    private final UserClient userClient;
    private final PatientClient patientClient;
    private final UserSnapshotStore userSnapshotStore;

    /**
     * 按用户 ID 重建并保存员工快照（用户不存在/禁用返回 null）
     *
     * @param userId 员工用户 ID
     * @return 快照；用户不存在/禁用返回 null
     */
    public UserSnapshot refresh(Long userId) {
        Result<AuthInfoDTO> result = userClient.authInfo(userId);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            userSnapshotStore.evict(SecurityConstants.IDENTITY_STAFF, userId);
            return null;
        }
        UserSnapshot snapshot = from(userId, result.getData());
        userSnapshotStore.save(snapshot);
        return snapshot;
    }

    /**
     * 患者快照重建（患者不存在/禁用返回 null；调用方持有 PatientClient）
     *
     * @param patientId 患者 ID
     * @return 快照；患者不存在/禁用返回 null
     */
    public UserSnapshot refreshPatient(Long patientId) {
        Result<AuthInfoDTO> result = patientClient.authInfo(patientId);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            userSnapshotStore.evict(SecurityConstants.IDENTITY_PATIENT, patientId);
            return null;
        }
        UserSnapshot snapshot = fromPatient(patientId, result.getData());
        userSnapshotStore.save(snapshot);
        return snapshot;
    }

    /**
     * 由员工登录返回的 AuthInfo 直接组装快照（登录路径，免二次查询）
     *
     * @param userId 员工用户 ID
     * @param info 登录返回的认证信息
     * @return 已保存的快照
     */
    public UserSnapshot saveFromLogin(Long userId, AuthInfoDTO info) {
        UserSnapshot snapshot = from(userId, info);
        userSnapshotStore.save(snapshot);
        return snapshot;
    }

    /**
     * 由患者登录返回的 AuthInfo 直接组装快照（登录路径，免二次查询）
     *
     * @param patientId 患者 ID
     * @param info 登录返回的认证信息
     * @return 已保存的快照
     */
    public UserSnapshot saveFromPatientLogin(Long patientId, AuthInfoDTO info) {
        UserSnapshot snapshot = fromPatient(patientId, info);
        userSnapshotStore.save(snapshot);
        return snapshot;
    }

    /**
     * 失效员工（默认身份）快照
     *
     * @param userId 员工用户 ID
     */
    public void evict(Long userId) {
        userSnapshotStore.evict(SecurityConstants.IDENTITY_STAFF, userId);
    }

    /**
     * 失效指定身份用户快照
     *
     * @param identity 身份类型（staff/patient）
     * @param userId 用户 ID
     */
    public void evict(String identity, Long userId) {
        userSnapshotStore.evict(identity, userId);
    }

    private UserSnapshot from(Long userId, AuthInfoDTO info) {
        return UserSnapshot.builder()
                .userId(userId)
                .identity(SecurityConstants.IDENTITY_STAFF)
                .username(info.getUser() == null ? null : info.getUser().getUsername())
                .name(info.getUser() == null ? null : info.getUser().getName())
                .roles(info.getRoles() == null ? List.of() : info.getRoles())
                .perms(info.getPerms() == null ? List.of() : info.getPerms())
                .hospitalId(info.getHospitalId())
                .departmentId(info.getDepartmentId())
                .build();
    }

    private UserSnapshot fromPatient(Long patientId, AuthInfoDTO info) {
        return UserSnapshot.builder()
                .userId(patientId)
                .identity(SecurityConstants.IDENTITY_PATIENT)
                .username(info.getUser() == null ? null : info.getUser().getUsername())
                .name(info.getUser() == null ? null : info.getUser().getName())
                .roles(List.of())
                .perms(List.of())
                .hospitalId(info.getHospitalId())
                .departmentId(null)
                .build();
    }
}
