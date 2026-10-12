package com.aiassistant.registration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 号源池。按排班预生成，每个号位一条。
 * <p>status 仅两态：1=空闲可约 2=已占用（占用时挂 registration_id）。
 * 号源余量 = 同排班下 status=1 的数量；锁定/释放均由挂号业务流转驱动。</p>
 */
@Data
@TableName("number_source")
public class NumberSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hospitalId;
    private Long departmentId;
    private Long doctorUserId;
    /** 关联 doctor_schedule */
    private Long scheduleId;
    private LocalDate scheduleDate;
    /** 1=上午 2=下午 */
    private Integer period;
    /** 就诊序号（1..total_limit） */
    private Integer seqNo;
    /** 占用时关联的挂号记录ID（空闲为 NULL） */
    private Long registrationId;
    /** 1=空闲可约 2=已占用 */
    private Integer status;
    private LocalDateTime createTime;
}
