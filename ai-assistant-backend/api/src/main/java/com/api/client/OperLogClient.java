package com.api.client;

import com.api.dto.OperLogDTO;
import com.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 操作日志上报客户端（system-service 内部接口）
 *
 * @author yh
 * @date 2026-10-14
 */
@FeignClient(name = "system-service", path = "/inner/oper-log", contextId = "operLogClient")
public interface OperLogClient {

    /**
     * 上报一条操作日志
     *
     * @param dto 日志数据
     * @return 空结果
     */
    @PostMapping
    Result<Void> report(@RequestBody OperLogDTO dto);
}
