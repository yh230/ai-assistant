package com.auth.qr.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 扫码场景创建结果
 *
 * @author yh
 * @date 2026-10-16
 */
@Data
@AllArgsConstructor
public class QrCreateVO {

    /** 场景标识 */
    private String sceneId;

    /** 有效期（秒） */
    private long expireSeconds;
}
