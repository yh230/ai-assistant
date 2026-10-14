package com.hospital.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 出勤表。
 * <p>
 * hospital_id / department_id 冗余存储员工所属医院与科室，便于按科室/医院直接过滤与统计，
 * 不必每次关联 sys_user。
 */
@Data
@TableName("attendance")
public class Attendance {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 员工ID（sys_user.id） */
    private Long userId;

    /** 医院ID（冗余） */
    private Long hospitalId;

    /** 科室ID（冗余） */
    private Long departmentId;

    /** 出勤日期 */
    private LocalDate attendDate;

    /** 出勤状态：1-出勤 2-缺勤 3-请假 4-迟到 */
    private Integer status;

    /** 上班打卡 HH:mm */
    private String checkIn;

    /** 下班打卡 HH:mm */
    private String checkOut;

    /** 备注 */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
