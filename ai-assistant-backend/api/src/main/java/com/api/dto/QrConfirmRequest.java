package com.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 扫码确认入参
 *
 * @author yh
 * @date 2026-10-16
 */
@Data
public class QrConfirmRequest {

    /** 扫码场景标识 */
    @NotBlank(message = "场景标识不能为空")
    private String sceneId;
}
