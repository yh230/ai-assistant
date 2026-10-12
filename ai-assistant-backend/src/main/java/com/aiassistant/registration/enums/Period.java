package com.aiassistant.registration.enums;

import com.aiassistant.common.exception.BusinessException;

import java.util.HashMap;
import java.util.Map;

/**
 * 出诊时段。
 */
public enum Period {

    AM(1, "上午"),
    PM(2, "下午");

    private final int code;
    private final String desc;

    Period(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, Period> BY_CODE = new HashMap<>();
    static {
        for (Period p : values()) BY_CODE.put(p.code, p);
    }

    public static Period of(int code) {
        Period p = BY_CODE.get(code);
        if (p == null) throw new BusinessException("未知时段: " + code);
        return p;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
