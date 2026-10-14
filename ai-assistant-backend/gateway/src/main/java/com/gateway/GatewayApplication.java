package com.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 微服务网关启动类
 *
 * @author yh
 * @date 2026-10-12
 */
@SpringBootApplication(scanBasePackages = {"com.gateway", "com.common"})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
