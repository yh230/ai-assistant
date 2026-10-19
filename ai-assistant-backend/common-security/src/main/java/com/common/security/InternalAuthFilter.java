package com.common.security;

import com.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部调用认证过滤器
 * <p>
 * 仅处理 {@code /inner/} 前缀请求：校验 {@code X-Internal-Token} 请求头与配置 {@code security.internal.token}
 * 一致（防止绕过网关直连服务内部接口），一致则放行（不解析用户 JWT），不一致直接输出 403 统一 Result JSON；
 * 令牌比较使用 {@link MessageDigest#isEqual} 常量时间比较，避免时序侧信道。
 * <p>
 * 路径判定使用 {@link HttpServletRequest#getServletPath()}（已剥离 context-path），
 * 避免部署在非根 context-path 下时 {@code /ctx/inner/**} 因 URI 前缀不匹配而绕过令牌校验。
 *
 * @author yh
 * @date 2026-10-12
 */
@RequiredArgsConstructor
public class InternalAuthFilter extends OncePerRequestFilter {

    /** 内部接口路径前缀 */
    private static final String INNER_PATH_PREFIX = "/inner/";

    private final String internalToken;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getServletPath().startsWith(INNER_PATH_PREFIX)) {
            String header = request.getHeader("X-Internal-Token");
            // 常量时间比较，避免按字符逐位比较导致的时序侧信道；空/未配置令牌一律拒绝
            byte[] expected = internalToken == null ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
            byte[] actual = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
            if (internalToken == null || internalToken.isEmpty() || !MessageDigest.isEqual(expected, actual)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                objectMapper.writeValue(response.getWriter(), Result.forbidden("权限不足，禁止访问"));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
