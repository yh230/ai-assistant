package com.aiassistant.registration.enums;

import com.aiassistant.common.exception.BusinessException;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付状态。
 */
public enum PayStatus {

    UNPAID(0, "未支付"),
    PAID(1, "已支付"),
    EXPIRED(2, "超时作废");

    private final int code;
    private final String desc;

    PayStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, PayStatus> BY_CODE = new HashMap<>();
    static {
        for (PayStatus s : values()) BY_CODE.put(s.code, s);
    }

    public static PayStatus of(int code) {
        PayStatus s = BY_CODE.get(code);
        if (s == null) throw new BusinessException("未知支付状态: " + code);
        return s;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
