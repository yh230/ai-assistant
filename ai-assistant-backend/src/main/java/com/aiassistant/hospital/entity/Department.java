package com.aiassistant.hospital.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 科室表。
 */
@Data
@TableName("department")
public class Department {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属医院ID */
    private Long hospitalId;

    /** 科室名称 */
    private String name;

    /** 科室主任用户ID（sys_user.id），可空 */
    private Long directorUserId;

    /** 状态：1-启用，0-禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
