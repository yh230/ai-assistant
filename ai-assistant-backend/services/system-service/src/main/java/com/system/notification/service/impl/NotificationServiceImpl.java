package com.system.notification.service.impl;

import com.api.mq.RegistrationMqEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.common.exception.BusinessException;
import com.common.result.PageResult;
import com.system.notification.entity.PatientNotification;
import com.system.notification.mapper.PatientNotificationMapper;
import com.system.notification.service.NotificationService;
import com.system.notification.vo.PatientNotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 患者站内信服务实现：事件文案渲染落库与管理端/患者端分页查询
 *
 * @author yh
 * @date 2026-10-14
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** 事件类型码到通知标题的映射 */
    private static final Map<String, String> TITLES = Map.of(
            RegistrationMqEvent.APPOINTMENT_CREATED, "预约成功",
            RegistrationMqEvent.APPOINTMENT_CANCELLED, "预约已取消",
            RegistrationMqEvent.REGISTRATION_REFUNDED, "退号成功",
            RegistrationMqEvent.APPOINTMENT_EXPIRED, "预约已失效",
            RegistrationMqEvent.SCHEDULE_CANCELLED, "停诊通知");

    /** 未读标记 */
    private static final int UNREAD = 0;

    /** 已读标记 */
    private static final int READ = 1;

    /** 未知事件类型兜底标题 */
    private static final String DEFAULT_TITLE = "就诊通知";

    private final PatientNotificationMapper notificationMapper;

    /**
     * 按事件类型渲染文案并落库一条站内信
     *
     * @param eventType 事件类型码
     * @param event 预约域事件体
     */
    @Override
    public void createFromEvent(String eventType, RegistrationMqEvent event) {
        PatientNotification entity = new PatientNotification();
        entity.setPatientId(event.getPatientId());
        entity.setHospitalId(event.getHospitalId());
        entity.setType(eventType);
        entity.setTitle(TITLES.getOrDefault(eventType, DEFAULT_TITLE));
        entity.setContent(renderContent(eventType, event));
        entity.setBizNo(event.getRegistrationNo() == null ? "" : event.getRegistrationNo());
        entity.setReadFlag(UNREAD);
        notificationMapper.insert(entity);
    }

    /**
     * 患者本人通知分页（按时间倒序，同一时刻按 ID 倒序）
     *
     * @param patientId 患者用户ID
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 通知分页结果
     */
    @Override
    public PageResult<PatientNotificationVO> pageForPatient(Long patientId, long pageNum, long pageSize) {
        Page<PatientNotification> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PatientNotification> qw = new LambdaQueryWrapper<>();
        qw.eq(PatientNotification::getPatientId, patientId)
          .orderByDesc(PatientNotification::getCreateTime)
          .orderByDesc(PatientNotification::getId);
        notificationMapper.selectPage(page, qw);
        return PageResult.of(page, page.getRecords().stream().map(this::toVO).toList());
    }

    /**
     * 患者本人未读通知数
     *
     * @param patientId 患者用户ID
     * @return 未读数
     */
    @Override
    public long unreadCount(Long patientId) {
        LambdaQueryWrapper<PatientNotification> qw = new LambdaQueryWrapper<>();
        qw.eq(PatientNotification::getPatientId, patientId)
          .eq(PatientNotification::getReadFlag, UNREAD);
        return notificationMapper.selectCount(qw);
    }

    /**
     * 标记本人某条通知为已读（条件更新，0 行视为不存在或越权）
     *
     * @param patientId 患者用户ID
     * @param id 通知ID
     */
    @Override
    public void markRead(Long patientId, Long id) {
        UpdateWrapper<PatientNotification> uw = new UpdateWrapper<>();
        uw.set("read_flag", READ)
          .eq("id", id)
          .eq("patient_id", patientId);
        if (notificationMapper.update(null, uw) == 0) {
            throw new BusinessException("通知不存在或无权操作");
        }
    }

    /**
     * 标记本人全部通知为已读
     *
     * @param patientId 患者用户ID
     */
    @Override
    public void readAll(Long patientId) {
        UpdateWrapper<PatientNotification> uw = new UpdateWrapper<>();
        uw.set("read_flag", READ)
          .eq("patient_id", patientId)
          .eq("read_flag", UNREAD);
        notificationMapper.update(null, uw);
    }

    /**
     * 管理端通知分页（hospitalId 非空按院过滤，空值平台管理员不过滤）
     *
     * @param hospitalId 操作者所属医院ID（可空）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 通知分页结果
     */
    @Override
    public PageResult<PatientNotificationVO> pageForAdmin(Long hospitalId, long pageNum, long pageSize) {
        Page<PatientNotification> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PatientNotification> qw = new LambdaQueryWrapper<>();
        qw.eq(hospitalId != null, PatientNotification::getHospitalId, hospitalId)
          .orderByDesc(PatientNotification::getCreateTime)
          .orderByDesc(PatientNotification::getId);
        notificationMapper.selectPage(page, qw);
        return PageResult.of(page, page.getRecords().stream().map(this::toVO).toList());
    }

    /**
     * 按事件类型渲染通知内容，空值的医生/科室片段自动省略，未知时段兜底为「时段」
     *
     * @param eventType 事件类型码
     * @param event 预约域事件体
     * @return 通知内容
     */
    private String renderContent(String eventType, RegistrationMqEvent event) {
        String dateTime = nz(event.getVisitDate()) + periodText(event.getPeriod());
        return switch (eventType) {
            case RegistrationMqEvent.APPOINTMENT_CREATED ->
                    "预约成功：" + withDeptDoctor(event) + dateTime + "，请按时就诊";
            case RegistrationMqEvent.APPOINTMENT_CANCELLED ->
                    "预约已取消：" + withDeptDoctor(event) + dateTime;
            case RegistrationMqEvent.REGISTRATION_REFUNDED ->
                    "退号成功：" + withDeptDoctor(event) + dateTime;
            case RegistrationMqEvent.APPOINTMENT_EXPIRED ->
                    "预约已失效：您在 " + dateTime + " 的预约因超时未支付已释放";
            case RegistrationMqEvent.SCHEDULE_CANCELLED ->
                    "停诊通知：您预约的 " + dateTime + " 门诊已停诊，请及时取消预约或联系医院改约";
            default -> dateTime;
        };
    }

    /**
     * 拼接「科室 医生 」片段（空值省略），非空时以空格结尾以便与后续日期时段分隔
     *
     * @param event 预约域事件体
     * @return 科室医生片段（可能为空串）
     */
    private String withDeptDoctor(RegistrationMqEvent event) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(event.getDepartmentName())) {
            parts.add(event.getDepartmentName());
        }
        if (StringUtils.hasText(event.getDoctorName())) {
            parts.add(event.getDoctorName());
        }
        return parts.isEmpty() ? "" : String.join(" ", parts) + " ";
    }

    /**
     * 时段文案：1=上午，2=下午，其他（含空值）兜底为「时段」
     *
     * @param period 时段编码
     * @return 时段文案
     */
    private String periodText(Integer period) {
        if (period == null) {
            return "时段";
        }
        return switch (period) {
            case 1 -> "上午";
            case 2 -> "下午";
            default -> "时段";
        };
    }

    /**
     * 空字符串兜底
     *
     * @param value 原始值
     * @return 非空字符串（null 转为空串）
     */
    private String nz(String value) {
        return value == null ? "" : value;
    }

    /**
     * 实体转视图对象（字段一一对应）
     *
     * @param entity 站内信实体
     * @return 站内信视图对象
     */
    private PatientNotificationVO toVO(PatientNotification entity) {
        PatientNotificationVO vo = new PatientNotificationVO();
        vo.setId(entity.getId());
        vo.setType(entity.getType());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setBizNo(entity.getBizNo());
        vo.setReadFlag(entity.getReadFlag());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
