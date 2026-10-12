package com.aiassistant.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 角色分配菜单权限请求。
 */
@Data
public class AssignMenuDTO {

    @NotNull(message = "菜单ID列表不能为空")
    private List<Long> menuIds;
}
