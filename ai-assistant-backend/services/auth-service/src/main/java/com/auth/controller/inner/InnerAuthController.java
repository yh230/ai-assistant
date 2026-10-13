package com.auth.controller.inner;

import com.auth.snapshot.SnapshotManager;
import com.common.auth.UserSnapshot;
import com.common.constant.SecurityConstants;
import com.common.jwt.TokenRevocationService;
import com.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证内部接口（仅供服务间 Feign 调用，网关阻断外部访问）
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/inner/auth")
@RequiredArgsConstructor
public class InnerAuthController {

    private final SnapshotManager snapshotManager;
    private final TokenRevocationService tokenRevocationService;

    /**
     * 失效指定身份用户快照（员工：角色/权限/绑定变更；患者：状态变更）
     *
     * @param identity 身份类型（staff/patient）
     * @param userId 用户 ID
     * @return 统一返回体
     */
    @PostMapping("/evict/{identity}/{userId}")
    public Result<Void> evict(@PathVariable("identity") String identity, @PathVariable("userId") Long userId) {
        snapshotManager.evict(identity, userId);
        return Result.success();
    }

    /**
     * 踢下线（写签发时间阈值并失效快照），下次请求立即 401
     *
     * @param identity 身份类型（staff/patient）
     * @param userId 用户 ID
     * @return 统一返回体
     */
    @PostMapping("/kickout/{identity}/{userId}")
    public Result<Void> kickout(@PathVariable("identity") String identity, @PathVariable("userId") Long userId) {
        tokenRevocationService.kickout(identity, userId);
        snapshotManager.evict(identity, userId);
        return Result.success();
    }

    /**
     * 读取（缺失则重建）指定身份用户快照；用户不存在/禁用返回 data=null
     *
     * @param identity 身份类型（staff/patient）
     * @param userId 用户 ID
     * @return 快照；用户不存在/禁用返回 data=null
     */
    @GetMapping("/snapshot/{identity}/{userId}")
    public Result<UserSnapshot> snapshot(@PathVariable("identity") String identity, @PathVariable("userId") Long userId) {
        UserSnapshot snapshot = SecurityConstants.IDENTITY_PATIENT.equals(identity)
                ? snapshotManager.refreshPatient(userId)
                : snapshotManager.refresh(userId);
        return Result.success(snapshot);
    }
}
