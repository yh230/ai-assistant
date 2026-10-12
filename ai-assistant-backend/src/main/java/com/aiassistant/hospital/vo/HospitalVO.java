package com.aiassistant.hospital.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 医院视图对象。
 */
@Data
public class HospitalVO {

    private Long id;
    private String name;
    private String address;
    private String phone;
    private Integer status;
    private LocalDateTime createTime;
}
