package com.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 公共配置：开启分页插件（单页上限 100，防止超大分页拖垮数据库）
 *
 * @author yh
 * @date 2026-10-12
 */
@Configuration
@ConditionalOnClass(com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor.class)
public class MybatisPlusAutoConfiguration {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限：无论调用方传入多大 pageSize，最多取 100 条
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
