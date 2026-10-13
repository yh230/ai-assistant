package com.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 用户中心服务启动类
 *
 * @author yh
 * @date 2026-10-12
 */
@SpringBootApplication(scanBasePackages = {"com.system", "com.common", "com.api"})
@EnableFeignClients("com.api.client")
@MapperScan("com.system.mapper")
public class SystemServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SystemServiceApplication.class, args);
    }
}
