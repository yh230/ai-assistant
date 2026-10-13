package com.common.security;

import com.api.client.AuthClient;
import com.common.auth.UserSnapshot;
import com.common.auth.UserSnapshotStore;
import com.common.constant.SecurityConstants;
import com.common.jwt.JwtTokenService;
import com.common.jwt.TokenRevocationService;
import com.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器（快照版）
 * <p>
 * 解析 Bearer 令牌，
 * 依次校验签名/过期、登出黑名单、踢下线阈值，再从权限快照（缺失经 AuthClient 重建）加载角色+权限写入 SecurityContext；
 * 校验不通过时不设置认证（由 EntryPoint 统一输出 401），黑名单/踢下线/快照不可用则直接输出 401，不在此抛异常
 *
 * @author yh
 * @date 2026-10-12
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final UserSnapshotStore userSnapshotStore;
    private final AuthClient authClient;
    private final ObjectMapper objectMapper;

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
                if (!usable) {
                    writeUnauthorized(response);
                    return;
                }
                UserSnapshot snapshot = userSnapshotStore.get(payload.userId());
                if (snapshot == null) {
                    snapshot = rebuildSnapshot(payload.userId());
                    if (snapshot == null) {
                        writeUnauthorized(response);
                        return;
                    }
                }
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                payload.userId(), null, authorities(snapshot));
                authentication.setDetails(snapshot);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // 无效/过期/被篡改令牌：不设置认证，由 EntryPoint 统一输出 401
                log.debug("JWT 校验失败: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 快照缺失时经 AuthClient 重建（服务端权限快照，成功则回写缓存）
     *
     * @param userId 用户 ID
     * @return 重建的快照；失败或用户不存在/禁用返回 null
     */
    private UserSnapshot rebuildSnapshot(Long userId) {
        try {
            Result<UserSnapshot> result = authClient.snapshot(userId);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                UserSnapshot snapshot = result.getData();
                userSnapshotStore.save(snapshot);
                return snapshot;
            }
        } catch (Exception e) {
            log.warn("重建用户权限快照失败: userId={}, {}", userId, e.getMessage());
        }
        return null;
    }

    /**
     * 组装角色码 + 权限标识（去重，来源为权限快照）
     *
     * @param snapshot 用户权限快照
     * @return authorities 列表
     */
    private List<GrantedAuthority> authorities(UserSnapshot snapshot) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (snapshot.getRoles() != null) {
            snapshot.getRoles().stream().filter(StringUtils::hasText).distinct()
                    .forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
        }
        if (snapshot.getPerms() != null) {
            snapshot.getPerms().stream().filter(StringUtils::hasText).distinct()
                    .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        }
        return authorities;
    }

    /**
     * 直接输出 401 统一 Result JSON（令牌被登出撤销/踢下线/用户不可用）
     *
     * @param response HTTP 响应
     * @throws IOException 写出异常
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.unauthorized("未登录或登录已过期，请重新登录"));
    }
}
