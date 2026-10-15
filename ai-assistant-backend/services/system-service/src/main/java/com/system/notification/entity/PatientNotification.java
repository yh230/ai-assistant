package com.system.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 患者站内信表：预约域 Kafka 事件渲染落库的通知
 *
 * @author yh
 * @date 2026-10-14
 */
@Data
@TableName("patient_notification")
public class PatientNotification {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 患者用户ID */
    private Long patientId;

    /** 归属医院ID（管理端过滤用） */
    private Long hospitalId;

    /** 通知类型（对应事件类型码） */
    private String type;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 关联业务号（挂号号等） */
    private String bizNo;

    /** 已读：0-未读，1-已读 */
    private Integer readFlag;

    /** 创建时间 */
    private LocalDateTime createTime;
}
