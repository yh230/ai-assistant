package com.api.client;

import com.api.dto.OrgBriefDTO;
import com.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 医院中心（hospital-service）内部接口客户端
 *
 * @author yh
 * @date 2026-10-12
 */
@FeignClient(name = "hospital-service", path = "/inner", contextId = "orgClient")
public interface OrgClient {

    /** 按 ID 批量查询医院简要信息 */
    @GetMapping("/hospital/batch")
    Result<List<OrgBriefDTO>> hospitals(@RequestParam("ids") List<Long> ids);

    /** 按 ID 批量查询科室简要信息 */
    @GetMapping("/department/batch")
    Result<List<OrgBriefDTO>> departments(@RequestParam("ids") List<Long> ids);

    /** 按医院查询科室列表 */
    @GetMapping("/department/list")
    Result<List<OrgBriefDTO>> departmentList(@RequestParam(value = "hospitalId", required = false) Long hospitalId);
}
