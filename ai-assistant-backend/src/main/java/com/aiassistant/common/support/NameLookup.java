package com.aiassistant.common.support;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ID → 名称批量回填支撑
 *
 * @author yh
 * @date 2026-10-10
 */
public final class NameLookup {

    private NameLookup() {
    }

    /**
     * 按 ID 集合批量查询 ID→名称 映射（跳过 null ID，重复 ID 取首个）
     *
     * @param ids ID 集合（可含 null）
     * @param idGetter 实体 ID 取值函数
     * @param nameGetter 实体名称取值函数
     * @param batchLoader 批量加载函数（如 {@code userMapper::selectBatchIds}）
     * @param <T> 实体类型
     * @return ID→名称 映射（无数据返回空 Map）
     */
    public static <T> Map<Long, String> namesByIds(Collection<Long> ids,
            Function<T, Long> idGetter, Function<T, String> nameGetter,
            Function<Collection<Long>, List<T>> batchLoader) {
        Set<Long> cleanIds = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (cleanIds.isEmpty()) {
            return Map.of();
        }
        return batchLoader.apply(cleanIds).stream()
                .collect(Collectors.toMap(idGetter, nameGetter, (first, second) -> first));
    }
}
