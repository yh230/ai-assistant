package com.aiassistant.security;

import com.aiassistant.common.constant.SecurityConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器
 * <p>
 * 变更点（Sa-Token→Spring Security 迁移）：替代原 SaInterceptor 的登录校验——解析 Bearer 令牌，
 * 依次校验签名/过期、登出黑名单、踢下线阈值，再从 DB 实时加载角色+权限写入 SecurityContext；
 * 校验不通过时不设置认证（由 EntryPoint 统一输出 401），不在此抛异常
 *
 * @author yh
 * @date 2026-10-12
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final DbUserDetailsService dbUserDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(SecurityConstants.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            String token = header.substring(SecurityConstants.TOKEN_PREFIX.length());
            try {
                JwtTokenService.JwtPayload payload = jwtTokenService.parse(token);
                long kickedAt = tokenRevocationService.kickoutAt(payload.userId());
                boolean usable = !tokenRevocationService.isRevoked(payload.jti())
                        && (kickedAt == 0 || payload.issuedAtMillis() > kickedAt);
                if (usable) {
                    DbUserDetailsService.LoginUser loginUser =
                            dbUserDetailsService.loadByUserId(payload.userId());
                    if (loginUser != null) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        loginUser, null, loginUser.authorities());
                        authentication.setDetails(payload);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (JwtException | IllegalArgumentException e) {
                // 无效/过期/被篡改令牌：不设置认证，由 EntryPoint 统一输出 401
                log.debug("JWT 校验失败: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
