package com.system.notification.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 患者站内信视图对象
 *
 * @author yh
 * @date 2026-10-14
 */
@Data
public class PatientNotificationVO {

    /** 主键 */
    private Long id;

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
