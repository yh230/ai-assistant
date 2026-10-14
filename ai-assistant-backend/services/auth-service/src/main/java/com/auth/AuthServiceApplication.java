package com.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 认证中心服务启动类
 *
 * @author yh
 * @date 2026-10-12
 */
@SpringBootApplication(scanBasePackages = {"com.auth", "com.common", "com.api"})
@EnableFeignClients("com.api.client")
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
