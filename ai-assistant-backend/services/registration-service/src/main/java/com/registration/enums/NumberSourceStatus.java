package com.registration.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * 号源状态
 *
 * @author yh
 * @date 2026-10-21
 */
public enum NumberSourceStatus {

    /** 空闲 */
    FREE(1, "空闲"),
    /** 已占用 */
    OCCUPIED(2, "已占用");

    private final int code;
    private final String desc;

    NumberSourceStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, NumberSourceStatus> BY_CODE = new HashMap<>();
    static {
        for (NumberSourceStatus s : values()) BY_CODE.put(s.code, s);
    }

    /**
     * 按编码解析号源状态
     *
     * @param code 状态编码（可空）
     * @return 对应枚举；未知或空返回 null
     */
    public static NumberSourceStatus of(Integer code) {
        return code == null ? null : BY_CODE.get(code);
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
