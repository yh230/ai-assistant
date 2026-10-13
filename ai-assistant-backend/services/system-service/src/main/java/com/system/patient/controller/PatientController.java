package com.system.patient.controller;

import com.common.result.Result;
import com.system.patient.dto.PatientRegisterDTO;
import com.system.patient.service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者账号接口（注册开放，网关白名单免登录）
 *
 * @author yh
 * @date 2026-10-13
 */
@RestController
@RequestMapping("/api/patient")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    /**
     * 注册患者账号
     *
     * @param dto 注册入参（手机号/密码/姓名/医院）
     * @return 新账号 ID
     */
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody PatientRegisterDTO dto) {
        return Result.success(patientService.register(dto));
    }
}
