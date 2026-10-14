package com.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.system.entity.SysOperLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper
 *
 * @author yh
 * @date 2026-10-14
 */
@Mapper
public interface SysOperLogMapper extends BaseMapper<SysOperLog> {
}
