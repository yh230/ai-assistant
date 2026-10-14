package com.system.controller.inner;

import com.api.dto.OperLogDTO;
import com.common.result.Result;
import com.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志内部接口（安全由 InternalAuthFilter 校验内部令牌承担）
 *
 * @author yh
 * @date 2026-10-14
 */
@RestController
@RequestMapping("/inner/oper-log")
@RequiredArgsConstructor
public class InnerOperLogController {

    private final OperLogService operLogService;

    /**
     * 接收各服务上报的操作日志并落库
     *
     * @param dto 日志数据
     * @return 空结果
     */
    @PostMapping
    public Result<Void> report(@RequestBody OperLogDTO dto) {
        operLogService.save(dto);
        return Result.success();
    }
}
