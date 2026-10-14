package com.system.service.impl;

import com.api.dto.OperLogDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.common.result.PageResult;
import com.system.entity.SysOperLog;
import com.system.mapper.SysOperLogMapper;
import com.system.service.OperLogService;
import com.system.vo.OperLogVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志服务实现：内部上报落库与管理端分页查询
 *
 * @author yh
 * @date 2026-10-14
 */
@Service
@RequiredArgsConstructor
public class OperLogServiceImpl implements OperLogService {

    private final SysOperLogMapper operLogMapper;

    /**
     * 保存一条上报日志（操作时间取落库时刻）
     *
     * @param dto 日志数据
     */
    @Override
    public void save(OperLogDTO dto) {
        SysOperLog entity = new SysOperLog();
        entity.setModule(dto.getModule());
        entity.setAction(dto.getAction());
        entity.setUserId(dto.getUserId());
        entity.setUsername(dto.getUsername());
        entity.setIdentity(dto.getIdentity());
        entity.setMethod(dto.getMethod());
        entity.setRequestUri(dto.getRequestUri());
        entity.setParams(dto.getParams());
        entity.setIp(dto.getIp());
        entity.setStatus(dto.getStatus());
        entity.setErrorMsg(dto.getErrorMsg());
        entity.setCostMs(dto.getCostMs());
        entity.setOperTime(LocalDateTime.now());
        operLogMapper.insert(entity);
    }

    /**
     * 操作日志分页查询（模块精确匹配，账号模糊匹配，日期区间为闭区间全天）
     *
     * @param module 业务模块（可空）
     * @param username 操作人账号（可空，模糊匹配）
     * @param status 结果（可空，0-成功 1-失败）
     * @param dateFrom 起始日期（可空，yyyy-MM-dd）
     * @param dateTo 结束日期（可空，yyyy-MM-dd）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 操作日志分页结果
     */
    @Override
    public PageResult<OperLogVO> page(String module, String username, Integer status,
                                      String dateFrom, String dateTo, long pageNum, long pageSize) {
        Page<SysOperLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysOperLog> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(module), SysOperLog::getModule, module)
          .like(StringUtils.hasText(username), SysOperLog::getUsername, username)
          .eq(status != null, SysOperLog::getStatus, status)
          .ge(StringUtils.hasText(dateFrom), SysOperLog::getOperTime, parseStart(dateFrom))
          .le(StringUtils.hasText(dateTo), SysOperLog::getOperTime, parseEnd(dateTo))
          .orderByDesc(SysOperLog::getOperTime)
          .orderByDesc(SysOperLog::getId);
        operLogMapper.selectPage(page, qw);
        List<OperLogVO> list = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(page, list);
    }

    /**
     * 起始日期解析为当日 00:00:00；为空返回 null（对应条件未启用，不参与 SQL）
     *
     * @param date 日期字符串 yyyy-MM-dd
     * @return 起始时间或 null
     */
    private LocalDateTime parseStart(String date) {
        return StringUtils.hasText(date) ? LocalDate.parse(date).atStartOfDay() : null;
    }

    /**
     * 结束日期解析为当日 23:59:59；为空返回 null（对应条件未启用，不参与 SQL）
     *
     * @param date 日期字符串 yyyy-MM-dd
     * @return 结束时间或 null
     */
    private LocalDateTime parseEnd(String date) {
        return StringUtils.hasText(date) ? LocalDate.parse(date).atTime(23, 59, 59) : null;
    }

    /**
     * 实体转视图对象（字段一一对应）
     *
     * @param entity 操作日志实体
     * @return 操作日志视图对象
     */
    private OperLogVO toVO(SysOperLog entity) {
        OperLogVO vo = new OperLogVO();
        vo.setId(entity.getId());
        vo.setModule(entity.getModule());
        vo.setAction(entity.getAction());
        vo.setUserId(entity.getUserId());
        vo.setUsername(entity.getUsername());
        vo.setIdentity(entity.getIdentity());
        vo.setMethod(entity.getMethod());
        vo.setRequestUri(entity.getRequestUri());
        vo.setParams(entity.getParams());
        vo.setIp(entity.getIp());
        vo.setStatus(entity.getStatus());
        vo.setErrorMsg(entity.getErrorMsg());
        vo.setCostMs(entity.getCostMs());
        vo.setOperTime(entity.getOperTime());
        return vo;
    }
}
