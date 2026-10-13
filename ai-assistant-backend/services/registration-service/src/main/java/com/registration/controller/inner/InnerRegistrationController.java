package com.registration.controller.inner;

import com.api.dto.SourceInfoDTO;
import com.common.result.Result;
import com.registration.service.NumberSourceService;
import com.registration.vo.NumberSourceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * 按科室与日期查询号源池（含余量，period 为空表示全部时段）
     *
     * @param departmentId 科室 ID
     * @param date 出诊日期（yyyy-MM-dd）
     * @param period 时段：1-上午，2-下午（可空）
     * @return 号源池简要信息列表
     */
    @GetMapping("/sources")
    public Result<List<SourceInfoDTO>> sources(@RequestParam("departmentId") Long departmentId,
                                               @RequestParam("date") String date,
                                               @RequestParam(value = "period", required = false) Integer period) {
        List<NumberSourceVO> pools = numberSourceService.listPools(null, departmentId, LocalDate.parse(date), period);
        return Result.success(toSourceInfoList(pools));
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
}
