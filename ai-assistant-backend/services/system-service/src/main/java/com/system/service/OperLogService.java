package com.system.service;

import com.api.dto.OperLogDTO;
import com.common.result.PageResult;
import com.system.vo.OperLogVO;

/**
 * 操作日志服务
 *
 * @author yh
 * @date 2026-10-14
 */
public interface OperLogService {

    /**
     * 保存一条上报日志
     *
     * @param dto 日志数据
     */
    void save(OperLogDTO dto);

    /**
     * 操作日志分页查询
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
    PageResult<OperLogVO> page(String module, String username, Integer status,
                               String dateFrom, String dateTo, long pageNum, long pageSize);
}
