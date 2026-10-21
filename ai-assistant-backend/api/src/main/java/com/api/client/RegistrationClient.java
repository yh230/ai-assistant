package com.api.client;

import com.api.dto.BookRequest;
import com.api.dto.CancelRequest;
import com.api.dto.RegistrationBriefDTO;
import com.api.dto.ScheduleQueryResultDTO;
import com.api.dto.SourceInfoDTO;
import com.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 挂号中心（registration-service）内部接口客户端
 *
 * @author yh
 * @date 2026-10-13
 */
@FeignClient(name = "registration-service", path = "/inner", contextId = "registrationClient")
public interface RegistrationClient {

    /** 按科室+日期查询号源池（含余量；hospitalId 必填按院约束） */
    @GetMapping("/sources")
    Result<List<SourceInfoDTO>> sources(@RequestParam("departmentId") Long departmentId,
                                        @RequestParam("date") String date,
                                        @RequestParam(value = "period", required = false) Integer period,
                                        @RequestParam("hospitalId") Long hospitalId);

    /** AI 预约挂号（写操作，调用方已做用户确认） */
    @PostMapping("/book")
    Result<Long> book(@RequestBody BookRequest req);

    /** AI 取消预约退号（写操作，调用方已做用户确认） */
    @PostMapping("/cancel")
    Result<Void> cancel(@RequestBody CancelRequest req);

    /** 查询某患者全部预约记录 */
    @GetMapping("/patient/{patientId}/registrations")
    Result<List<RegistrationBriefDTO>> patientRegistrations(@PathVariable("patientId") Long patientId);

    /** 按医院+日期窗口查询排班与余量（对外 MCP 只读查询） */
    @GetMapping("/schedules/query")
    Result<ScheduleQueryResultDTO> querySchedules(@RequestParam("hospitalId") Long hospitalId,
                                                  @RequestParam("dateFrom") String dateFrom,
                                                  @RequestParam("dateTo") String dateTo,
                                                  @RequestParam(value = "departmentId", required = false) Long departmentId,
                                                  @RequestParam(value = "period", required = false) Integer period,
                                                  @RequestParam(value = "doctorName", required = false) String doctorName);
}
