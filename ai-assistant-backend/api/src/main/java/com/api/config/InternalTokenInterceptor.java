package com.api.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;

/**
 * Feign 内部调用拦截器：统一附加内部令牌头（服务端按此校验，防止绕过网关直连）
 *
 * @author yh
 * @date 2026-10-12
 */
public class InternalTokenInterceptor implements RequestInterceptor {

    @Value("${security.internal.token:}")
    private String internalToken;

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Internal-Token", internalToken);
    }
}
