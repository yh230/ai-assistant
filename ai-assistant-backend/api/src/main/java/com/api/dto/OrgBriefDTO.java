package com.api.dto;

import lombok.Data;

/**
 * 医院/科室简要信息（跨服务名称回填共用）
 *
 * @author yh
 * @date 2026-10-12
 */
@Data
public class OrgBriefDTO {

    /** 组织 ID（医院或科室） */
    private Long id;

    /** 组织名称 */
    private String name;

    /** 所属医院 ID（科室回填医院名称时使用） */
    private Long hospitalId;
}
