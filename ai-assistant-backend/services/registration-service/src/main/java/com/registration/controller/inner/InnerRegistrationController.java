package com.registration.controller.inner;

import com.api.dto.BookRequest;
import com.api.dto.CancelRequest;
import com.api.dto.RegistrationBriefDTO;
import com.api.dto.SourceInfoDTO;
import com.common.result.Result;
import com.registration.dto.AppointmentCreateDTO;
import com.registration.service.NumberSourceService;
import com.registration.service.RegistrationService;
import com.registration.vo.NumberSourceVO;
import com.registration.vo.RegistrationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 挂号中心内部接口（仅供服务间 Feign 调用，网关阻断外部访问）
 *
 * @author yh
 * @date 2026-10-13
 */
@RestController
@RequestMapping("/inner")
@RequiredArgsConstructor
public class InnerRegistrationController {

    private final NumberSourceService numberSourceService;
    private final RegistrationService registrationService;

    /**
     * 按科室与日期查询号源池（含余量，period 为空表示全部时段，hospitalId 非空时按院约束）
     *
     * @param departmentId 科室 ID
     * @param date 出诊日期（yyyy-MM-dd）
     * @param period 时段：1-上午，2-下午（可空）
     * @param hospitalId 医院 ID（可空；调用方传入以按院约束）
     * @return 号源池简要信息列表
     */
    @GetMapping("/sources")
    public Result<List<SourceInfoDTO>> sources(@RequestParam("departmentId") Long departmentId,
                                               @RequestParam("date") String date,
                                               @RequestParam(value = "period", required = false) Integer period,
                                               @RequestParam(value = "hospitalId", required = false) Long hospitalId) {
        List<NumberSourceVO> pools = numberSourceService.listPools(hospitalId, null, departmentId, LocalDate.parse(date), period);
        return Result.success(toSourceInfoList(pools));
    }

    /**
     * AI 预约挂号（写操作，调用方已完成用户二次确认）
     *
     * @param req 预约入参（患者账号、医生、日期、时段）
     * @return 新挂号记录 ID
     */
    @PostMapping("/book")
    public Result<Long> book(@RequestBody BookRequest req) {
        AppointmentCreateDTO dto = new AppointmentCreateDTO();
        dto.setPatientId(req.getPatientId());
        dto.setPatientName(req.getPatientName());
        dto.setPatientPhone(req.getPatientPhone());
        dto.setDoctorUserId(req.getDoctorUserId());
        dto.setScheduleDate(LocalDate.parse(req.getScheduleDate()));
        dto.setPeriod(req.getPeriod());
        dto.setSeqNo(req.getSeqNo());
        return Result.success(registrationService.createAppointment(dto).getId());
    }

    /**
     * AI 取消预约退号（写操作，调用方已完成用户二次确认）
     *
     * @param req 取消入参（患者账号、挂号记录 ID、取消原因）
     * @return 空结果
     */
    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestBody CancelRequest req) {
        registrationService.cancelByPatient(req.getRegistrationId(), req.getPatientId(), req.getReason());
        return Result.success();
    }

    /**
     * 某患者全部预约记录（AI「我的预约」工具）
     *
     * @param patientId 患者账号 ID
     * @return 预约记录简要信息列表
     */
    @GetMapping("/patient/{patientId}/registrations")
    public Result<List<RegistrationBriefDTO>> patientRegistrations(@PathVariable("patientId") Long patientId) {
        return Result.success(toBriefList(registrationService.listByPatientId(patientId)));
    }

    /**
     * 号源池视图转内部简要 DTO（静态方法便于单测）
     *
     * @param pools 号源池列表
     * @return 内部简要 DTO 列表
     */
    static List<SourceInfoDTO> toSourceInfoList(List<NumberSourceVO> pools) {
        return pools.stream().map(vo -> {
            SourceInfoDTO dto = new SourceInfoDTO();
            dto.setScheduleId(vo.getScheduleId());
            dto.setDoctorUserId(vo.getDoctorUserId());
            dto.setDoctorName(vo.getDoctorName());
            dto.setScheduleDate(vo.getScheduleDate() == null ? null : vo.getScheduleDate().toString());
            dto.setPeriod(vo.getPeriod());
            dto.setPeriodText(vo.getPeriodText());
            dto.setTotalLimit(vo.getTotalLimit());
            dto.setUsedCount(vo.getUsedCount());
            dto.setRemainingCount(vo.getRemainingCount());
            return dto;
        }).toList();
    }

    /**
     * 预约记录转内部简要 DTO（静态方法便于单测）
     *
     * @param records 挂号记录视图列表
     * @return 内部简要 DTO 列表
     */
    static List<RegistrationBriefDTO> toBriefList(List<RegistrationVO> records) {
        return records.stream().map(vo -> {
            RegistrationBriefDTO dto = new RegistrationBriefDTO();
            dto.setId(vo.getId());
            dto.setRegistrationNo(vo.getRegistrationNo());
            dto.setDepartmentId(vo.getDepartmentId());
            dto.setDepartmentName(vo.getDepartmentName());
            dto.setDoctorName(vo.getDoctorName());
            dto.setScheduleDate(vo.getScheduleDate() == null ? null : vo.getScheduleDate().toString());
            dto.setPeriod(vo.getPeriod());
            dto.setPeriodText(vo.getPeriodText());
            dto.setSeqNo(vo.getSeqNo());
            dto.setStatus(vo.getStatus());
            dto.setStatusText(vo.getStatusText());
            dto.setExpireTime(vo.getExpireTime() == null ? null : vo.getExpireTime().toString());
            return dto;
        }).toList();
    }
}
