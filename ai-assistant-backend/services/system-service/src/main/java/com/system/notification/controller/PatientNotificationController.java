package com.system.notification.controller;

import com.common.result.PageResult;
import com.common.result.Result;
import com.common.security.SecurityUtils;
import com.system.notification.service.NotificationService;
import com.system.notification.vo.PatientNotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者站内信接口（患者身份，取当前登录患者ID）
 *
 * @author yh
 * @date 2026-10-14
 */
@RestController
@RequestMapping("/api/patient/notifications")
@RequiredArgsConstructor
public class PatientNotificationController {

    private final NotificationService notificationService;

    /**
     * 本人通知分页（按时间倒序）
     *
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 通知分页数据
     */
    @GetMapping
    public Result<PageResult<PatientNotificationVO>> list(@RequestParam(defaultValue = "1") long pageNum,
                                                          @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(notificationService.pageForPatient(SecurityUtils.getCurrentUserId(), pageNum, pageSize));
    }

    /**
     * 本人未读通知数
     *
     * @return 未读数
     */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(notificationService.unreadCount(SecurityUtils.getCurrentUserId()));
    }

    /**
     * 标记本人某条通知为已读
     *
     * @param id 通知ID
     * @return 空结果
     */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(SecurityUtils.getCurrentUserId(), id);
        return Result.success();
    }

    /**
     * 标记本人全部通知为已读
     *
     * @return 空结果
     */
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        notificationService.readAll(SecurityUtils.getCurrentUserId());
        return Result.success();
    }
}
