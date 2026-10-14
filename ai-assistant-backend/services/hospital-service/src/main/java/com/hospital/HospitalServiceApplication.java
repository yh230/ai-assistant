package com.hospital;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 医院中心服务启动类
 *
 * @author yh
 * @date 2026-10-12
 */
@SpringBootApplication(scanBasePackages = {"com.hospital", "com.common", "com.api"})
@EnableFeignClients("com.api.client")
@MapperScan("com.hospital.mapper")
public class HospitalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HospitalServiceApplication.class, args);
    }
}
