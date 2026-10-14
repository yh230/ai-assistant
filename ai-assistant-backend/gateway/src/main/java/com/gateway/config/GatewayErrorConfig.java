package com.gateway.config;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关异常响应统一为 Result 结构（路由不存在 404 / 限流 429 / 下游不可用 503）
 *
 * @author yh
 * @date 2026-10-12
 */
@Configuration
public class GatewayErrorConfig {

    @Bean
    public ErrorWebExceptionHandler resultErrorWebExceptionHandler() {
        return (exchange, ex) -> {
            HttpStatus status;
            if (ex instanceof NotFoundException) {
                status = HttpStatus.NOT_FOUND;
            } else if (ex instanceof ResponseStatusException statusEx
                    && HttpStatus.TOO_MANY_REQUESTS.equals(statusEx.getStatusCode())) {
                status = HttpStatus.TOO_MANY_REQUESTS;
            } else {
                status = HttpStatus.SERVICE_UNAVAILABLE;
            }
            String msg = switch (status) {
                case NOT_FOUND -> "资源不存在";
                case TOO_MANY_REQUESTS -> "请求过于频繁，请稍后再试";
                default -> "服务暂不可用，请稍后重试";
            };
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"code\":" + status.value() + ",\"msg\":\"" + msg + "\",\"data\":null}";
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        };
    }
}
