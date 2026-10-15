package com.system.notification.controller;

import com.common.auth.UserSnapshot;
import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.SecurityUtils;
import com.system.notification.service.NotificationService;
import com.system.notification.vo.PatientNotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者站内信查询接口（管理端，仅预留查询，不做页面）
 *
 * @author yh
 * @date 2026-10-14
 */
@RestController
@RequestMapping("/api/system/patient-notification")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationService notificationService;

    /**
     * 站内信分页（按操作者所属医院过滤，平台管理员不过滤）
     *
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 通知分页数据
     */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:notification:list')")
    public Result<PageResult<PatientNotificationVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                          @RequestParam(defaultValue = "10") long pageSize) {
        Long hospitalId = resolveHospitalId();
        return Result.success(notificationService.pageForAdmin(hospitalId, pageNum, pageSize));
    }

    /**
     * 读取当前操作者所属医院ID（未登录或平台管理员返回 null，表示不过滤）
     *
     * @return 医院ID或 null
     */
    private Long resolveHospitalId() {
        UserSnapshot snapshot = SecurityUtils.currentSnapshot();
        return snapshot == null ? null : snapshot.getHospitalId();
    }
}
