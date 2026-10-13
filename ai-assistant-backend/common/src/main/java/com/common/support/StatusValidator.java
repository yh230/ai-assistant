package com.common.support;

import com.common.constant.StatusConstants;
import com.common.exception.BusinessException;

/**
 * 启用/禁用状态校验
 *
 * @author yh
 * @date 2026-10-10
 */
public final class StatusValidator {

    private StatusValidator() {
    }

    /**
     * 校验状态值必须为启用(1)/禁用(0)
     *
     * @param status 状态值
     * @param failMessage 非法时的提示文案
     */
    public static void checkEnabledDisabled(Integer status, String failMessage) {
        if (status == null
                || (!StatusConstants.ENABLED.equals(status) && !StatusConstants.DISABLED.equals(status))) {
            throw new BusinessException(failMessage);
        }
    }
}
