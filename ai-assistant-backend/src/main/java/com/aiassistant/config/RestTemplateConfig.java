package com.aiassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * RestTemplate 配置 - 用于调用外部 API
 * <p>
 * 统一添加 User-Agent 请求头，避免外部 API（如高德开放平台）
 * 因识别到 Java 默认 UA 而返回 403 Forbidden。
 */
@Configuration
public class RestTemplateConfig {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);

        RestTemplate restTemplate = new RestTemplate(factory);

        // 拦截器：为所有请求统一注入 User-Agent
        ClientHttpRequestInterceptor userAgentInterceptor = (request, body, execution) -> {
            request.getHeaders().set("User-Agent", USER_AGENT);
            request.getHeaders().set("Accept", "application/json");
            return execution.execute(request, body);
        };
        restTemplate.setInterceptors(List.of(userAgentInterceptor));

        return restTemplate;
    }
}
