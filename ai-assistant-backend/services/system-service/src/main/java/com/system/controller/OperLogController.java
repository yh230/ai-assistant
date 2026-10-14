package com.system.controller;

import com.common.result.PageResult;
import com.common.result.Result;
import com.system.service.OperLogService;
import com.system.vo.OperLogVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询接口（管理端）
 *
 * @author yh
 * @date 2026-10-14
 */
@RestController
@RequestMapping("/api/system/oper-log")
@RequiredArgsConstructor
public class OperLogController {

    private final OperLogService operLogService;

    /**
     * 操作日志分页
     *
     * @param module 业务模块（可空）
     * @param username 操作人账号（可空）
     * @param status 结果（可空）
     * @param dateFrom 起始日期 yyyy-MM-dd（可空）
     * @param dateTo 结束日期 yyyy-MM-dd（可空）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 操作日志分页数据
     */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:operlog:list')")
    public Result<PageResult<OperLogVO>> page(@RequestParam(required = false) String module,
                                              @RequestParam(required = false) String username,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String dateFrom,
                                              @RequestParam(required = false) String dateTo,
                                              @RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(operLogService.page(module, username, status, dateFrom, dateTo, pageNum, pageSize));
    }
}
