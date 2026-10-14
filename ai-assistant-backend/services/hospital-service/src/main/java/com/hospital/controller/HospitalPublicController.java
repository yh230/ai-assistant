package com.hospital.controller;

import com.api.dto.OrgBriefDTO;
import com.common.result.Result;
import com.hospital.service.HospitalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 医院公开接口（患者注册选院用，网关白名单免登录，公开只读）
 *
 * @author yh
 * @date 2026-10-13
 */
@RestController
@RequestMapping("/api/hospital/public")
@RequiredArgsConstructor
public class HospitalPublicController {

    private final HospitalService hospitalService;

    /**
     * 医院公开列表（患者注册选院用）
     *
     * @return 全部医院简要信息（id + name）
     */
    @GetMapping("/list")
    public Result<List<OrgBriefDTO>> list() {
        return Result.success(hospitalService.listBrief());
    }
}
