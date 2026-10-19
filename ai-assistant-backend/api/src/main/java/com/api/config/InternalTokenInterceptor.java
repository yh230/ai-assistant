package com.api.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 内部调用拦截器：统一附加内部令牌头（服务端按此校验，防止绕过网关直连）；
 * 若当前存在 HTTP 请求上下文，则同时透传调用人 {@code Authorization} 头，
 * 使下游内部接口能够识别操作人
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
        // MQ/定时任务等无请求上下文的场景不追加，避免 NPE 与伪造身份
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            String authorization = servletAttributes.getRequest().getHeader("Authorization");
            if (StringUtils.hasText(authorization)) {
                template.header("Authorization", authorization);
            }
        }
    }
}
