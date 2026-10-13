package com.gateway.filter;

import com.common.constant.SecurityConstants;
import com.common.jwt.JwtTokenService;
import com.common.jwt.TokenRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关鉴权过滤器：验签 + 黑名单/踢下线校验 + 放行清单 + /inner 外部阻断 + 按身份拦截患者/员工路径
 *
 * @author yh
 * @date 2026-10-12
 */
@Component
@RequiredArgsConstructor
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String UNAUTHORIZED_MSG = "未登录或登录已过期，请重新登录";
    private static final String FORBIDDEN_MSG = "权限不足，禁止访问";

    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService tokenRevocationService;

    /** 放行清单（Ant 风格前缀匹配，见 application.yml） */
    @Value("${gateway.whitelist:}")
    private List<String> whitelist;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod()) || matchesWhitelist(path)) {
            return chain.filter(exchange);
        }
        if (path.startsWith("/inner/")) {
            return writeResult(exchange, HttpStatus.FORBIDDEN, FORBIDDEN_MSG);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return writeResult(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MSG);
        }
        try {
            JwtTokenService.JwtPayload payload = jwtTokenService.parse(authorization.substring(7));
            long kickedAt = tokenRevocationService.kickoutAt(payload.identity(), payload.userId());
            boolean kicked = kickedAt != 0 && payload.issuedAtMillis() <= kickedAt;
            if (tokenRevocationService.isRevoked(payload.jti()) || kicked) {
                return writeResult(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MSG);
            }
            String identity = payload.identity();
            boolean patient = SecurityConstants.IDENTITY_PATIENT.equals(identity);
            boolean patientPath = isPatientPath(path);
            if (patient != patientPath) {
                return writeResult(exchange, HttpStatus.FORBIDDEN, FORBIDDEN_MSG);
            }
        } catch (Exception e) {
            return writeResult(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MSG);
        }
        return chain.filter(exchange);
    }

    /** 患者专属路径：/api/patient/** 与 /api/chat/patient/**（login/register 已在白名单直放） */
    private boolean isPatientPath(String path) {
        return path.startsWith("/api/patient/") || path.startsWith("/api/chat/patient/");
    }

    private boolean matchesWhitelist(String path) {
        for (String pattern : whitelist) {
            if (pattern.endsWith("/**") && path.startsWith(pattern.substring(0, pattern.length() - 3))) {
                return true;
            }
            if (pattern.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> writeResult(ServerWebExchange exchange, HttpStatus status, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + status.value() + ",\"msg\":\"" + msg + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
