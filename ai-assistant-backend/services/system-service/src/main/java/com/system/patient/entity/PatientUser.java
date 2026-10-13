package com.system.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 患者账号实体（与员工 sys_user 物理隔离，独立登录名/密码体系）
 *
 * @author yh
 * @date 2026-10-13
 */
@Data
@TableName("patient_user")
public class PatientUser {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 手机号（登录名） */
    private String phone;

    /** 密码（BCrypt 密文） */
    private String password;

    /** 患者姓名 */
    private String name;

    /** 绑定医院 ID */
    private Long hospitalId;

    /** 状态：1=正常 0=禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
