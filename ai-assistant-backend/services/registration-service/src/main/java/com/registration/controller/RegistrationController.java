package com.registration.controller;

import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.operlog.OperLog;
import com.registration.dto.AppointmentCreateDTO;
import com.registration.dto.CancelDTO;
import com.registration.dto.PayConfirmDTO;
import com.registration.dto.RefundDTO;
import com.registration.dto.RegistrationQueryDTO;
import com.registration.dto.WalkInRegisterDTO;
import com.registration.service.RegistrationService;
import com.registration.vo.RegistrationVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 挂号 / 预约 / 取号接口
 * <ul>
 *     <li>公开接口（患者免登录）：线上预约、查询本人、确认支付、取消预约。</li>
 *     <li>员工接口（按角色 + 数据权限）：挂号记录、现场挂号、取号、退号、标记就诊、统计。</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-12
 */
@RestController
@RequestMapping("/api/registration")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    // ===================== 公开（患者 / 自助机查询本人） =====================

    @OperLog(module = "患者预约", action = "线上预约")
    @PostMapping("/appointments")
    public Result<RegistrationVO> createAppointment(@Valid @RequestBody AppointmentCreateDTO dto) {
        // 匿名公开入口不接受 patientId，防止归属冒用
        dto.setPatientId(null);
        return Result.success(registrationService.createAppointment(dto));
    }

    @GetMapping("/appointments/query")
    public Result<List<RegistrationVO>> patientQuery(@RequestParam(required = false) String phone,
                                                     @RequestParam(required = false) String idCard,
                                                     @RequestParam(required = false) String registrationNo) {
        return Result.success(registrationService.patientQuery(phone, idCard, registrationNo));
    }

    @OperLog(module = "患者预约", action = "确认支付")
    @PostMapping("/appointments/{id}/confirm-pay")
    public Result<RegistrationVO> confirmPay(@PathVariable Long id, @Valid @RequestBody PayConfirmDTO dto) {
        return Result.success(registrationService.confirmPay(id, dto.getPatientIdCard()));
    }

    @OperLog(module = "患者预约", action = "取消预约")
    @PostMapping("/appointments/{id}/cancel")
    public Result<RegistrationVO> patientCancel(@PathVariable Long id, @Valid @RequestBody CancelDTO dto) {
        return Result.success(registrationService.patientCancel(id, dto.getPatientIdCard(), dto.getReason()));
    }

    // ===================== 员工（鉴权 + 数据权限） =====================

    @GetMapping("/records")
    @PreAuthorize("hasAuthority('registration:register:list')")
    public Result<PageResult<RegistrationVO>> page(@RequestParam(required = false) Long doctorUserId,
                                                   @RequestParam(required = false) Long departmentId,
                                                   @RequestParam(required = false) Long hospitalId,
                                                   @RequestParam(required = false) String patientPhone,
                                                   @RequestParam(required = false) String patientName,
                                                   @RequestParam(required = false) Integer status,
                                                   @RequestParam(required = false) Integer period,
                                                   @RequestParam(required = false) String dateFrom,
                                                   @RequestParam(required = false) String dateTo,
                                                   @RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "10") long pageSize) {
        RegistrationQueryDTO q = new RegistrationQueryDTO();
        q.setDoctorUserId(doctorUserId);
        q.setDepartmentId(departmentId);
        q.setHospitalId(hospitalId);
        q.setPatientPhone(patientPhone);
        q.setPatientName(patientName);
        q.setStatus(status);
        q.setPeriod(period);
        q.setPageNum(pageNum);
        q.setPageSize(pageSize);
        if (dateFrom != null && !dateFrom.isBlank()) q.setDateFrom(LocalDate.parse(dateFrom));
        if (dateTo != null && !dateTo.isBlank()) q.setDateTo(LocalDate.parse(dateTo));
        return Result.success(registrationService.page(q));
    }

    @GetMapping("/records/{id}")
    @PreAuthorize("hasAuthority('registration:register:list')")
    public Result<RegistrationVO> get(@PathVariable Long id) {
        return Result.success(registrationService.get(id));
    }

    @OperLog(module = "挂号管理", action = "现场挂号")
    @PostMapping("/records/walk-in")
    @PreAuthorize("hasAuthority('registration:register:create')")
    public Result<RegistrationVO> walkIn(@Valid @RequestBody WalkInRegisterDTO dto) {
        return Result.success(registrationService.walkIn(dto));
    }

    @OperLog(module = "挂号管理", action = "取号")
    @PostMapping("/records/{id}/take")
    @PreAuthorize("hasAuthority('registration:register:take')")
    public Result<RegistrationVO> take(@PathVariable Long id) {
        return Result.success(registrationService.take(id));
    }

    @OperLog(module = "挂号管理", action = "退号")
    @PostMapping("/records/{id}/refund")
    @PreAuthorize("hasAuthority('registration:register:refund')")
    public Result<RegistrationVO> refund(@PathVariable Long id, @RequestBody RefundDTO dto) {
        return Result.success(registrationService.refund(id, dto.getReason()));
    }

    @OperLog(module = "挂号管理", action = "标记就诊")
    @PostMapping("/records/{id}/treat")
    @PreAuthorize("hasAuthority('registration:register:treat')")
    public Result<RegistrationVO> treat(@PathVariable Long id) {
        return Result.success(registrationService.treat(id));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('registration:stats:view')")
    public Result<Map<String, Object>> stats(@RequestParam(defaultValue = "doctor") String dimension) {
        return Result.success(registrationService.stats(dimension));
    }
}
