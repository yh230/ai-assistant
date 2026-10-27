package com.aiassistant.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * AI HTTP 客户端超时配置：统一设置连接/读取超时，避免模型调用无限阻塞。
 * <p>
 * Spring AI 手动构建 {@code OpenAiApi} 时会使用注入的 {@link org.springframework.web.client.RestClient.Builder}
 * 与 {@link org.springframework.web.reactive.function.client.WebClient.Builder}；这里通过 Boot 的
 * customizer 把超时应用到这两个 builder 上。
 *
 * @author yh
 * @date 2026-10-27
 */
@Configuration
public class AiHttpClientConfig {

    /**
     * RestClient 超时（非流式调用）。
     *
     * @param props AI 配置
     * @return RestClient 定制器
     */
    @Bean
    public RestClientCustomizer aiRestClientCustomizer(AiProperties props) {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(
                    props.getChat().getHttpConnectTimeoutSeconds()).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(
                    props.getChat().getHttpReadTimeoutSeconds()).toMillis());
            builder.requestFactory(factory);
        };
    }

    /**
     * WebClient 超时（流式调用）。响应超时取总超时 + 30 秒，避免早于上层截断逻辑。
     *
     * @param props AI 配置
     * @return WebClient 定制器
     */
    @Bean
    public WebClientCustomizer aiWebClientCustomizer(AiProperties props) {
        return builder -> {
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Duration.ofSeconds(
                            props.getChat().getHttpConnectTimeoutSeconds()).toMillis())
                    .responseTimeout(Duration.ofSeconds(
                            props.getChat().getOverallTimeoutSeconds() + 30L));
            builder.clientConnector(new ReactorClientHttpConnector(httpClient));
        };
    }
}
