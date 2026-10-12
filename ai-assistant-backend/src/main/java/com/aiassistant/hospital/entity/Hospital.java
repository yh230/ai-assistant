package com.aiassistant.hospital.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 医院表。
 */
@Data
@TableName("hospital")
public class Hospital {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 医院名称 */
    private String name;

    /** 地址 */
    private String address;

    /** 联系电话 */
    private String phone;

    /** 状态：1-启用，0-禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
