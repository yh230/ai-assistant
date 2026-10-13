package com.api.client;

import com.common.auth.UserSnapshot;
import com.common.constant.SecurityConstants;
import com.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 认证中心（auth-service）内部接口客户端
 *
 * @author yh
 * @date 2026-10-12
 */
@FeignClient(name = "auth-service", path = "/inner/auth", contextId = "authClient")
public interface AuthClient {

    /** 失效指定身份用户的权限缓存快照 */
    @PostMapping("/evict/{identity}/{userId}")
    Result<Void> evict(@PathVariable("identity") String identity, @PathVariable("userId") Long userId);

    /** 强制下线指定身份用户 */
    @PostMapping("/kickout/{identity}/{userId}")
    Result<Void> kickout(@PathVariable("identity") String identity, @PathVariable("userId") Long userId);

    /** 查询指定身份用户的权限快照 */
    @GetMapping("/snapshot/{identity}/{userId}")
    Result<UserSnapshot> snapshot(@PathVariable("identity") String identity, @PathVariable("userId") Long userId);

    /** 失效员工（默认身份）权限缓存快照 */
    default Result<Void> evict(Long userId) {
        return evict(SecurityConstants.IDENTITY_STAFF, userId);
    }

    /** 强制员工（默认身份）下线 */
    default Result<Void> kickout(Long userId) {
        return kickout(SecurityConstants.IDENTITY_STAFF, userId);
    }

    /** 查询员工（默认身份）权限快照 */
    default Result<UserSnapshot> snapshot(Long userId) {
        return snapshot(SecurityConstants.IDENTITY_STAFF, userId);
    }
}
