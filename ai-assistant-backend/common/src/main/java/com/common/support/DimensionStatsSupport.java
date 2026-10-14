package com.common.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 维度统计结果组装支撑（groupStats 原始行 → 前端结构）
 *
 * @author yh
 * @date 2026-10-10
 */
public final class DimensionStatsSupport {

    private DimensionStatsSupport() {
    }

    /**
     * 组装统计返回结构
     *
     * @param dimension 统计维度
     * @param rows groupStats 原始行（key：dim / cnt）
     * @param stringDimension 维度值是否为字符串（如日期），是则直接取 dim 字符串且不输出 dimId
     * @param nameResolver 数值维度下 dimId→dimName 的解析函数（dimId 已做 -1 兜底）
     * @return {dimension, rows:[{dimId?, cnt, dimName}]}
     */
    public static Map<String, Object> assemble(String dimension, List<Map<String, Object>> rows,
            boolean stringDimension, Function<Long, String> nameResolver) {
        List<Map<String, Object>> resultRows = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object dimObj = r.get("dim");
            Long cnt = r.get("cnt") == null ? 0L : ((Number) r.get("cnt")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            if (stringDimension) {
                row.put("dimName", dimObj == null ? "未知" : dimObj.toString());
            } else {
                Long dimId = dimObj == null ? -1L : ((Number) dimObj).longValue();
                row.put("dimId", dimId);
                row.put("dimName", nameResolver.apply(dimId));
            }
            row.put("cnt", cnt);
            resultRows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("rows", resultRows);
        return result;
    }
}
