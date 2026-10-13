package com.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * api 模块装配：注册内部令牌拦截器（Feign 客户端由各服务启动类 @EnableFeignClients("com.api.client") 启用）
 *
 * @author yh
 * @date 2026-10-12
 */
@Configuration
public class ApiAutoConfiguration {

    @Bean
    public InternalTokenInterceptor internalTokenInterceptor() {
        return new InternalTokenInterceptor();
    }
}
