package com.aiassistant.registration.enums;

import com.aiassistant.common.exception.BusinessException;

import java.util.HashMap;
import java.util.Map;

/**
 * 挂号来源渠道。
 */
public enum SourceType {

    ONLINE(1, "线上预约"),
    WINDOW(2, "现场窗口"),
    KIOSK(3, "自助机");

    private final int code;
    private final String desc;

    SourceType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, SourceType> BY_CODE = new HashMap<>();
    static {
        for (SourceType s : values()) BY_CODE.put(s.code, s);
    }

    public static SourceType of(int code) {
        SourceType s = BY_CODE.get(code);
        if (s == null) throw new BusinessException("未知挂号来源: " + code);
        return s;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
