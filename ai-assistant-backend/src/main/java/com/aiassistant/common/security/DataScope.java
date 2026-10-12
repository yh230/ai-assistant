package com.aiassistant.common.security;

import com.aiassistant.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import lombok.Getter;

/**
 * 数据权限范围
 * <p>
 * 变更点：由 hospital/security 上移至 common/security，供医院与挂号模块共用；新增归属判定与查询条件收敛方法
 * <ul>
 *     <li>ALL：超级管理员，全部数据（系统级最高权限）</li>
 *     <li>HOSPITAL：院长/门诊管理角色，仅本人所属医院数据</li>
 *     <li>DEPARTMENT：科室主任，仅本科室数据</li>
 *     <li>SELF：医生/护士/护工等，仅本人数据</li>
 * </ul>
 *
 * @author yh
 * @date 2026-10-10
 */
@Getter
public class DataScope {

    /** 范围类型 */
    public enum Type { ALL, HOSPITAL, DEPARTMENT, SELF }

    private final Type type;
    private final Long hospitalId;
    private final Long departmentId;
    private final Long userId;

    private DataScope(Type type, Long hospitalId, Long departmentId, Long userId) {
        this.type = type;
        this.hospitalId = hospitalId;
        this.departmentId = departmentId;
        this.userId = userId;
    }

    /**
     * 全部数据范围（超级管理员/未登录内部调用）
     *
     * @return 全部范围
     */
    public static DataScope all() {
        return new DataScope(Type.ALL, null, null, null);
    }

    /**
     * 本院范围：按医院 ID 过滤
     *
     * @param hospitalId 医院 ID
     * @return 本院范围
     */
    public static DataScope hospital(Long hospitalId) {
        return new DataScope(Type.HOSPITAL, hospitalId, null, null);
    }

    /**
     * 本科室范围：按科室 ID 过滤，并携带医院 ID 以便按本院收敛列表
     *
     * @param departmentId 科室 ID
     * @param hospitalId 医院 ID
     * @return 本科室范围
     */
    public static DataScope department(Long departmentId, Long hospitalId) {
        return new DataScope(Type.DEPARTMENT, hospitalId, departmentId, null);
    }

    /**
     * 本人范围
     *
     * @param userId 用户 ID
     * @return 本人范围
     */
    public static DataScope self(Long userId) {
        return new DataScope(Type.SELF, null, null, userId);
    }

    public boolean isAll() {
        return type == Type.ALL;
    }

    public boolean isHospital() {
        return type == Type.HOSPITAL;
    }

    public boolean isDepartment() {
        return type == Type.DEPARTMENT;
    }

    public boolean isSelf() {
        return type == Type.SELF;
    }

    /**
     * 判断指定数据归属是否在当前范围内
     *
     * @param hospitalId 数据的医院 ID
     * @param departmentId 数据的科室 ID
     * @param userId 数据的归属用户 ID
     * @return true 表示在范围内可访问
     */
    public boolean canAccess(Long hospitalId, Long departmentId, Long userId) {
        return switch (type) {
            case ALL -> true;
            case HOSPITAL -> hospitalId != null && hospitalId.equals(this.hospitalId);
            case DEPARTMENT -> departmentId != null && departmentId.equals(this.departmentId);
            case SELF -> userId != null && userId.equals(this.userId);
        };
    }

    /**
     * 断言数据归属在当前范围内，越权时抛出业务异常
     *
     * @param hospitalId 数据的医院 ID
     * @param departmentId 数据的科室 ID
     * @param userId 数据的归属用户 ID
     * @param failMessage 越权提示文案
     */
    public void assertCanAccess(Long hospitalId, Long departmentId, Long userId, String failMessage) {
        if (!canAccess(hospitalId, departmentId, userId)) {
            throw new BusinessException(failMessage);
        }
    }

    /**
     * 把范围条件追加到查询构造器（各业务表传入自己的列，ALL 范围不加条件）
     *
     * @param qw 查询构造器
     * @param hospitalColumn 医院列
     * @param departmentColumn 科室列
     * @param userColumn 归属用户列
     * @param <T> 实体类型
     */
    public <T> void applyTo(LambdaQueryWrapper<T> qw, SFunction<T, ?> hospitalColumn,
            SFunction<T, ?> departmentColumn, SFunction<T, ?> userColumn) {
        if (isHospital()) {
            qw.eq(hospitalColumn, hospitalId);
        } else if (isDepartment()) {
            qw.eq(departmentColumn, departmentId);
        } else if (isSelf()) {
            qw.eq(userColumn, userId);
        }
    }

    /**
     * 生成范围条件的 SQL 片段（供自定义统计 SQL 使用；值均为内部 Long，无注入面）
     *
     * @param hospitalColumn 医院列名
     * @param departmentColumn 科室列名
     * @param userColumn 归属用户列名
     * @return 条件 SQL，如 {@code hospital_id = 3}；ALL 范围返回 {@code 1=1}
     */
    public String toWhereSql(String hospitalColumn, String departmentColumn, String userColumn) {
        if (isHospital()) {
            return hospitalColumn + " = " + hospitalId;
        } else if (isDepartment()) {
            return departmentColumn + " = " + departmentId;
        } else if (isSelf()) {
            return userColumn + " = " + userId;
        }
        return "1=1";
    }
}
