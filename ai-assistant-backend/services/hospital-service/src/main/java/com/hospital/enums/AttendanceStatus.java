package com.hospital.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * 出勤状态
 *
 * @author yh
 * @date 2026-10-10
 */
public enum AttendanceStatus {

    /** 出勤 */
    PRESENT(1, "出勤"),
    /** 缺勤 */
    ABSENT(2, "缺勤"),
    /** 请假 */
    LEAVE(3, "请假"),
    /** 迟到 */
    LATE(4, "迟到");

    private final int code;
    private final String desc;

    AttendanceStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, AttendanceStatus> BY_CODE = new HashMap<>();
    static {
        for (AttendanceStatus s : values()) BY_CODE.put(s.code, s);
    }

    /**
     * 按编码解析出勤状态
     *
     * @param code 状态编码（可空）
     * @return 对应枚举；未知或空返回 null
     */
    public static AttendanceStatus of(Integer code) {
        return code == null ? null : BY_CODE.get(code);
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
