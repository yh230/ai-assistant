package com.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页查询统一返回结构
 *
 * @param <T> 行数据类型
 * @author yh
 * @date 2026-10-10
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private long pageNum;

    /** 每页大小 */
    private long pageSize;

    /** 当前页数据集合 */
    private List<T> list;

    public static <T> PageResult<T> of(long total, long pageNum, long pageSize, List<T> list) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setList(list);
        return result;
    }

    /**
     * 以 MyBatis-Plus 分页对象与转换后的行数据构建分页结果
     *
     * @param page 分页查询结果
     * @param list 当前页数据集合
     * @param <T> 行数据类型
     * @return 分页返回结构
     */
    public static <T> PageResult<T> of(IPage<?> page, List<T> list) {
        return of(page.getTotal(), page.getCurrent(), page.getSize(), list);
    }
}
