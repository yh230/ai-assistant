package com.system.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.system.notification.entity.PatientNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 患者站内信 Mapper
 *
 * @author yh
 * @date 2026-10-14
 */
@Mapper
public interface PatientNotificationMapper extends BaseMapper<PatientNotification> {
}
