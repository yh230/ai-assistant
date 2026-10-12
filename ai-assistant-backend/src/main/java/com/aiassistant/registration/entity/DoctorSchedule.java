package com.aiassistant.registration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 医生出诊排班 / 号源配置。
 * <p>门诊管理员维护；定时任务据此每天生成号源。排班改了需作废旧号源并重生成。</p>
 */
@Data
@TableName("doctor_schedule")
public class DoctorSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 医院ID（冗余） */
    private Long hospitalId;
    /** 科室ID（冗余） */
    private Long departmentId;
    /** 医生用户ID（关联 sys_user） */
    private Long doctorUserId;
    /** 出诊日期 */
    private LocalDate scheduleDate;
    /** 时段 1=上午 2=下午（见 {@link com.aiassistant.registration.enums.Period}） */
    private Integer period;
    /** 号源总数 */
    private Integer totalLimit;
    /** 已挂号数（冗余） */
    private Integer registeredCount;
    /** 状态 1=启用 0=停用/作废 */
    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
