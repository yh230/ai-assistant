package com.common.security.operlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;

/**
 * 操作审计注解：标注控制器写操作方法，由 OperLogAspect 采集并上报
 *
 * @author yh
 * @date 2026-10-14
 */
@Target(METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {

    /**
     * 业务模块
     *
     * @return 模块名（如「挂号管理」）
     */
    String module();

    /**
     * 操作动作
     *
     * @return 动作名（如「退号」）
     */
    String action();
}
