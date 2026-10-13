package com.registration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 挂号 / 预约 / 取号记录（状态机核心载体）。
 * <p>状态机见 {@link com.registration.enums.RegistrationStatus}：
 * 已预约(锁定) → 已取号 → 已就诊 / 已取消 / 已退号 / 过期失效。</p>
 */
@Data
@TableName("registration")
public class Registration {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 挂号凭证号（业务单号，唯一） */
    private String registrationNo;
    private Long hospitalId;
    private Long departmentId;
    private Long doctorUserId;
    private Long scheduleId;
    private Long numberSourceId;
    private LocalDate scheduleDate;
    /** 1=上午 2=下午 */
    private Integer period;
    /** 就诊序号（取自号源） */
    private Integer seqNo;

    private String patientName;
    private String patientPhone;
    private String patientIdCard;

    /** 来源 1=线上预约 2=现场窗口 3=自助机（见 SourceType） */
    private Integer sourceType;
    /** 0=未支付 1=已支付 2=超时作废（见 PayStatus） */
    private Integer payStatus;
    private BigDecimal payAmount;
    private LocalDateTime payTime;

    /** 状态机：1=已预约 2=已取号 3=已就诊 4=已取消 5=已退号 6=过期失效（见 RegistrationStatus） */
    private Integer status;
    /** 预约锁定过期时间（支付/取号时限） */
    private LocalDateTime expireTime;
    private LocalDateTime treatTime;
    private BigDecimal refundAmount;
    private LocalDateTime refundTime;

    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
