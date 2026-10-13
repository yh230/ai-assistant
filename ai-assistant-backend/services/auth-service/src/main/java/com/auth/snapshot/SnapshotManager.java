package com.auth.snapshot;

import com.api.client.UserClient;
import com.api.dto.AuthInfoDTO;
import com.common.auth.UserSnapshot;
import com.common.auth.UserSnapshotStore;
import com.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 权限快照管理：组装（调用户中心）/失效（auth 为快照唯一属主）
 *
 * @author yh
 * @date 2026-10-12
 */
@Component
@RequiredArgsConstructor
public class SnapshotManager {

    private final UserClient userClient;
    private final UserSnapshotStore userSnapshotStore;

    /** 按用户 ID 重建并保存快照（用户不存在/禁用返回 null） */
    public UserSnapshot refresh(Long userId) {
        Result<AuthInfoDTO> result = userClient.authInfo(userId);
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            userSnapshotStore.evict(userId);
            return null;
        }
        UserSnapshot snapshot = from(userId, result.getData());
        userSnapshotStore.save(snapshot);
        return snapshot;
    }

    /** 由登录返回的 AuthInfo 直接组装快照（登录路径，免二次查询） */
    public UserSnapshot saveFromLogin(Long userId, AuthInfoDTO info) {
        UserSnapshot snapshot = from(userId, info);
        userSnapshotStore.save(snapshot);
        return snapshot;
    }

    /** 失效快照 */
    public void evict(Long userId) {
        userSnapshotStore.evict(userId);
    }

    private UserSnapshot from(Long userId, AuthInfoDTO info) {
        return UserSnapshot.builder()
                .userId(userId)
                .username(info.getUser() == null ? null : info.getUser().getUsername())
                .name(info.getUser() == null ? null : info.getUser().getName())
                .roles(info.getRoles() == null ? List.of() : info.getRoles())
                .perms(info.getPerms() == null ? List.of() : info.getPerms())
                .hospitalId(info.getHospitalId())
                .departmentId(info.getDepartmentId())
                .build();
    }
}
