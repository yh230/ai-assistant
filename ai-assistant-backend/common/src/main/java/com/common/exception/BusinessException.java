package com.common.exception;

import lombok.Getter;

/**
 * 业务异常：由业务规则主动抛出，由 {@link GlobalExceptionHandler} 统一包装为 {@link Result}。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务状态码，默认 500 */
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
