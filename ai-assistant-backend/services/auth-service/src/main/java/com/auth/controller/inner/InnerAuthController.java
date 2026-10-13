package com.auth.controller.inner;

import com.auth.snapshot.SnapshotManager;
import com.common.auth.UserSnapshot;
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

    /** 失效用户权限快照（角色/权限/绑定变更时由 system 调用） */
    @PostMapping("/evict/{userId}")
    public Result<Void> evict(@PathVariable("userId") Long userId) {
        snapshotManager.evict(userId);
        return Result.success();
    }

    /** 踢下线（写签发时间阈值并失效快照），下次请求立即 401 */
    @PostMapping("/kickout/{userId}")
    public Result<Void> kickout(@PathVariable("userId") Long userId) {
        tokenRevocationService.kickout(userId);
        snapshotManager.evict(userId);
        return Result.success();
    }

    /** 读取（缺失则重建）用户快照；用户不存在/禁用返回 data=null */
    @GetMapping("/snapshot/{userId}")
    public Result<UserSnapshot> snapshot(@PathVariable("userId") Long userId) {
        return Result.success(snapshotManager.refresh(userId));
    }
}
