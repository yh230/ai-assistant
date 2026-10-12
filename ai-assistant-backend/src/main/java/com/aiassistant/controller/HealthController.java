package com.aiassistant.controller;

import com.aiassistant.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 系统健康检查接口
 *
 * @author yh
 * @date 2026-10-10
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class HealthController {

    /**
     * 健康检查
     * GET /api/health
     *
     * @return 服务健康状态信息
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of(
                "status", "UP",
                "service", "AI Assistant",
                "version", "1.0.0",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
