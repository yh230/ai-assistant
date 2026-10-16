package com.system.patient.controller.inner;

import com.api.dto.AuthInfoDTO;
import com.api.dto.AuthRequest;
import com.api.dto.UserBriefDTO;
import com.common.result.Result;
import com.system.patient.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 患者中心内部接口（仅供服务间 Feign 调用，网关阻断外部访问）
 *
 * @author yh
 * @date 2026-10-13
 */
@RestController
@RequestMapping("/inner/patient")
@RequiredArgsConstructor
public class InnerPatientController {

    private final PatientService patientService;

    /**
     * 患者登录凭据校验并返回快照数据
     *
     * @param req 登录入参（username 为手机号）
     * @return 权限快照数据
     */
    @PostMapping("/authenticate")
    public Result<AuthInfoDTO> authenticate(@RequestBody AuthRequest req) {
        return Result.success(patientService.authenticate(req));
    }

    /**
     * 按患者 ID 组装快照数据（不存在或禁用返回 null）
     *
     * @param id 患者 ID
     * @return 权限快照数据
     */
    @GetMapping("/{id}/auth-info")
    public Result<AuthInfoDTO> authInfo(@PathVariable("id") Long id) {
        return Result.success(patientService.authInfo(id));
    }

    /**
     * 按手机号查患者权限快照（未绑定返回 data=null）
     *
     * @param phone 手机号
     * @return 权限快照数据
     */
    @GetMapping("/by-phone")
    public Result<AuthInfoDTO> byPhone(@RequestParam("phone") String phone) {
        return Result.success(patientService.authInfoByPhone(phone));
    }

    /**
     * 按 ID 批量查询患者简要信息
     *
     * @param ids 患者 ID 集合
     * @return 患者简要信息列表
     */
    @GetMapping("/batch")
    public Result<List<UserBriefDTO>> batch(@RequestParam("ids") List<Long> ids) {
        return Result.success(patientService.batchBrief(ids));
    }
}
