package com.api.client;

import com.common.auth.UserSnapshot;
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

    /** 失效指定用户的权限缓存快照 */
    @PostMapping("/evict/{userId}")
    Result<Void> evict(@PathVariable("userId") Long userId);

    /** 强制下线指定用户 */
    @PostMapping("/kickout/{userId}")
    Result<Void> kickout(@PathVariable("userId") Long userId);

    /** 查询用户权限快照 */
    @GetMapping("/snapshot/{userId}")
    Result<UserSnapshot> snapshot(@PathVariable("userId") Long userId);
}
