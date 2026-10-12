package com.aiassistant.registration.enums;

import com.aiassistant.common.exception.BusinessException;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 挂号记录状态机（固定枚举，需求强制约束）。
 * <pre>
 *   号源空闲(号源池 status=1)
 *        │ 预约/现场挂号占用一个空闲号源
 *        ▼
 *   [1] 已预约(锁定)  ──取号──▶  [2] 已取号 ──就诊──▶ [3] 已就诊
 *        │                              │
 *        │ 取消预约(释放号源)           │ 退号(未就诊,退款,号源回收)
 *        ▼                              ▼
 *   [4] 已取消                    [5] 已退号
 *        │
 *        │ 超时未支付/未取号(释放号源)
 *        ▼
 *   [6] 过期失效
 *
 *   现场窗口挂号：创建即 [2] 已取号（号源占用，不产生"已预约"中间态）。
 *   终态：已就诊 / 已取消 / 已退号 / 过期失效 均不可再流转。
 * </pre>
 */
public enum RegistrationStatus {

    RESERVED(1, "已预约"),
    CALLED(2, "已取号"),
    TREATED(3, "已就诊"),
    CANCELLED(4, "已取消"),
    REFUNDED(5, "已退号"),
    EXPIRED(6, "过期失效");

    private final int code;
    private final String desc;

    RegistrationStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, RegistrationStatus> BY_CODE = new HashMap<>();
    static {
        for (RegistrationStatus s : values()) BY_CODE.put(s.code, s);
    }

    public static RegistrationStatus of(int code) {
        RegistrationStatus s = BY_CODE.get(code);
        if (s == null) throw new BusinessException("未知挂号状态: " + code);
        return s;
    }

    /** 合法状态流转矩阵：key=当前态，value=可流转到的目标态集合 */
    private static final Map<RegistrationStatus, Set<RegistrationStatus>> TRANSITIONS = new HashMap<>();
    static {
        TRANSITIONS.put(RESERVED, EnumSet.of(CALLED, CANCELLED, EXPIRED));
        TRANSITIONS.put(CALLED, EnumSet.of(TREATED, REFUNDED));
        // TREATED / CANCELLED / REFUNDED / EXPIRED 为终态，无出边
    }

    /** 是否允许从当前态流转到 target */
    public boolean canTransitTo(RegistrationStatus target) {
        Set<RegistrationStatus> next = TRANSITIONS.get(this);
        return next != null && next.contains(target);
    }

    /** 校验流转合法性，非法直接抛业务异常（状态机核心保护） */
    public void assertTransit(RegistrationStatus target, String action) {
        if (!canTransitTo(target)) {
            throw new BusinessException(
                    String.format("挂号状态不允许从[%s]流转到[%s]（操作：%s）", desc, target.desc, action));
        }
    }

    /** 是否为终态 */
    public boolean isTerminal() {
        return !TRANSITIONS.containsKey(this);
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
