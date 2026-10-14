package com.hospital.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 医院新增/编辑请求。
 */
@Data
public class HospitalSaveDTO {

    @NotBlank(message = "医院名称不能为空")
    private String name;

    private String address;

    private String phone;

    /** 状态：1-启用，0-禁用，默认启用 */
    private Integer status = 1;
}
