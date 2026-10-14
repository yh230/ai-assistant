package com.common.security.operlog;

import com.api.client.OperLogClient;
import com.api.dto.OperLogDTO;
import com.common.auth.UserSnapshot;
import com.common.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计切面：采集登录人/请求/参数/结果耗时，经 OperLogClient 上报 system-service
 * <p>
 * 上报为 best-effort：任何上报异常仅记 warn，不阻断业务；业务异常原样重抛
 *
 * @author yh
 * @date 2026-10-14
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperLogAspect {

    private final OperLogClient operLogClient;
    private final ObjectMapper objectMapper;

    /**
     * 环绕被 @OperLog 标注的方法采集审计日志
     *
     * @param joinPoint 连接点
     * @param operLog 注解实例
     * @return 原方法返回值
     * @throws Throwable 原方法抛出的异常（原样透传）
     */
    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperLog operLog) throws Throwable {
        long start = System.nanoTime();
        Throwable error = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                operLogClient.report(buildDto(joinPoint, operLog, error, (System.nanoTime() - start) / 1_000_000));
            } catch (Exception e) {
                log.warn("操作日志上报失败: {}", e.getMessage());
            }
        }
    }

    private OperLogDTO buildDto(ProceedingJoinPoint joinPoint, OperLog operLog, Throwable error, long costMs) {
        OperLogDTO dto = new OperLogDTO();
        dto.setModule(operLog.module());
        dto.setAction(operLog.action());
        fillUser(dto);
        fillRequest(dto);
        dto.setParams(OperLogSanitizer.argsToJson(joinPoint.getArgs(), objectMapper));
        dto.setStatus(error == null ? 0 : 1);
        dto.setErrorMsg(error == null ? "" : OperLogSanitizer.truncate(error.getMessage(), 500));
        dto.setCostMs(costMs);
        return dto;
    }

    private void fillUser(OperLogDTO dto) {
        try {
            UserSnapshot snapshot = SecurityUtils.currentSnapshot();
            if (snapshot != null) {
                dto.setUserId(snapshot.getUserId());
                dto.setUsername(snapshot.getUsername());
                dto.setIdentity(snapshot.getIdentity());
            }
        } catch (Exception ignored) {
            // 匿名/未登录场景无快照，仅记录 IP 与参数
        }
    }

    private void fillRequest(OperLogDTO dto) {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest request = attrs.getRequest();
                dto.setMethod(request.getMethod());
                dto.setRequestUri(request.getRequestURI());
                dto.setIp(clientIp(request));
            }
        } catch (Exception ignored) {
            // 非 Web 上下文（如单元测试）跳过请求信息
        }
    }

    /**
     * 取真实客户端 IP：X-Forwarded-For 末跳（网关追加、客户端无法伪造），缺失回退远端地址
     *
     * @param request HTTP 请求
     * @return 客户端 IP（截断 64 字符）
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
            if (!last.isEmpty()) {
                return OperLogSanitizer.truncate(last, 64);
            }
        }
        return OperLogSanitizer.truncate(request.getRemoteAddr(), 64);
    }
}
