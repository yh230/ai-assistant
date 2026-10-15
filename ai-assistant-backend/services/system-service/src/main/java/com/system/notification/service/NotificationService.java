package com.system.notification.service;

import com.api.mq.RegistrationMqEvent;
import com.common.result.PageResult;
import com.system.notification.vo.PatientNotificationVO;

/**
 * 患者站内信服务：事件渲染落库与患者/管理端查询
 *
 * @author yh
 * @date 2026-10-14
 */
public interface NotificationService {

    /**
     * 按事件类型渲染文案并落库一条站内信
     *
     * @param eventType 事件类型码
     * @param event 预约域事件体
     */
    void createFromEvent(String eventType, RegistrationMqEvent event);

    /**
     * 患者本人通知分页（按时间倒序）
     *
     * @param patientId 患者用户ID
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 通知分页结果
     */
    PageResult<PatientNotificationVO> pageForPatient(Long patientId, long pageNum, long pageSize);

    /**
     * 患者本人未读通知数
     *
     * @param patientId 患者用户ID
     * @return 未读数
     */
    long unreadCount(Long patientId);

    /**
     * 标记本人某条通知为已读（校验归属）
     *
     * @param patientId 患者用户ID
     * @param id 通知ID
     */
    void markRead(Long patientId, Long id);

    /**
     * 标记本人全部通知为已读
     *
     * @param patientId 患者用户ID
     */
    void readAll(Long patientId);

    /**
     * 管理端通知分页（hospitalId 为空表示平台管理员，不做医院过滤）
     *
     * @param hospitalId 操作者所属医院ID（可空）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 通知分页结果
     */
    PageResult<PatientNotificationVO> pageForAdmin(Long hospitalId, long pageNum, long pageSize);
}
