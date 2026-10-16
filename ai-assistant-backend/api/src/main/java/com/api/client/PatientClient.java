package com.api.client;

import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.UserBriefDTO;
import com.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 患者中心（system-service）内部接口客户端
 *
 * @author yh
 * @date 2026-10-13
 */
@FeignClient(name = "system-service", path = "/inner/patient", contextId = "patientClient")
public interface PatientClient {

    /** 患者登录凭据校验并返回快照数据（BCrypt 校验在 system 侧完成） */
    @PostMapping("/authenticate")
    Result<AuthInfoDTO> authenticate(@RequestBody AuthRequest req);

    /** 按患者 ID 组装快照数据 */
    @GetMapping("/{id}/auth-info")
    Result<AuthInfoDTO> authInfo(@PathVariable("id") Long id);

    /** 按手机号查患者权限快照（患者手机号登录用，未绑定返回 data=null） */
    @GetMapping("/by-phone")
    Result<AuthInfoDTO> byPhone(@RequestParam("phone") String phone);

    /** 按 ID 批量查询患者简要信息 */
    @GetMapping("/batch")
    Result<List<UserBriefDTO>> batch(@RequestParam("ids") List<Long> ids);
}
