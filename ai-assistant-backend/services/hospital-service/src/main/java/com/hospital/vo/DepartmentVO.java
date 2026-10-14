package com.hospital.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 科室视图对象（含医院名称、主任姓名）。
 */
@Data
public class DepartmentVO {

    private Long id;
    private Long hospitalId;
    private String hospitalName;
    private String name;
    private Long directorUserId;
    private String directorName;
    private Integer status;
    private LocalDateTime createTime;
}
