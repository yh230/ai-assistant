package com.common.security;

import com.common.exception.BusinessException;
import com.common.result.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;

/**
 * 全局异常处理器：将所有异常统一包装为 {@link Result} 结构返回，
 * 避免向前端暴露原生异常堆栈或 HTTP 状态文本
 *
 * <p>{@code RuntimeException}/{@code Exception} 兜底分支不透出
 * {@code e.getMessage()}，统一返回「系统繁忙，请稍后重试」，完整堆栈仅记录到日志。</p>
 *
 * @author yh
 * @date 2026-10-12
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理认证失败（过滤器之外的方法级/服务内认证异常）
     *
     * @param e 认证异常
     * @return 401 返回体
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuthentication(AuthenticationException e) {
        return Result.unauthorized("未登录或登录已过期，请重新登录");
    }

    /**
     * 处理权限不足（@PreAuthorize 方法级拒绝）
     *
     * @param e 访问拒绝异常
     * @return 403 返回体
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.forbidden("权限不足，禁止访问");
    }

    /** 非法参数 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return Result.fail(400, e.getMessage());
    }

    /** 参数校验失败（@Valid 标注的 @RequestBody） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = resolveFieldError(e.getBindingResult().getFieldError());
        return Result.fail(400, message);
    }

    /**
     * 请求参数类型不匹配（如路径/查询参数应为数字却传了非数字）
     *
     * @param e 类型不匹配异常
     * @return 400 返回体
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail(400, "参数类型错误：" + e.getName());
    }

    /**
     * 日期解析失败（如 yyyy-MM-dd 格式不正确）
     *
     * @param e 日期解析异常
     * @return 400 返回体
     */
    @ExceptionHandler(DateTimeParseException.class)
    public Result<Void> handleDateTimeParse(DateTimeParseException e) {
        return Result.fail(400, "日期格式错误，应为 yyyy-MM-dd");
    }

    /** 参数校验失败（@ModelAttribute / 表单） */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = resolveFieldError(e.getBindingResult().getFieldError());
        return Result.fail(400, message);
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(400, "缺少必填参数：" + e.getParameterName());
    }

    /** 请求体无法解析（如 JSON 格式错误） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return Result.fail(400, "请求体格式错误");
    }

    /**
     * Bean Validation 约束违反（路径/参数级）：仅返回字段名 + 校验文案，不透出内部路径
     *
     * @param e 约束违反异常
     * @return 400 返回体
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations() == null || e.getConstraintViolations().isEmpty()
                ? "参数校验失败"
                : resolveViolation(e.getConstraintViolations().iterator().next());
        return Result.fail(400, message);
    }

    /** 请求方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(405, "不支持的请求方法：" + e.getMethod());
    }

    /**
     * 兜底：运行时异常（固定文案返回，避免透出内部异常信息）
     *
     * @param e 运行时异常
     * @return 统一失败结果
     */
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常: {}", e.getMessage(), e);
        return Result.fail(500, "系统繁忙，请稍后重试");
    }

    /**
     * 兜底：未预期的异常（固定文案返回，避免透出内部异常信息）
     *
     * @param e 未预期的异常
     * @return 统一失败结果
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(500, "系统繁忙，请稍后重试");
    }

    private String resolveFieldError(FieldError fieldError) {
        if (fieldError == null) {
            return "参数校验失败";
        }
        String message = fieldError.getDefaultMessage();
        return message == null || message.isBlank()
                ? fieldError.getField() + " 参数校验失败"
                : fieldError.getField() + " " + message;
    }

    /**
     * 解析单条约束违反信息：取属性路径末段作为字段名，方法参数占位（arg0）时仅返回校验文案
     *
     * @param violation 约束违反项
     * @return 字段名 + 校验文案
     */
    private String resolveViolation(ConstraintViolation<?> violation) {
        String message = violation.getMessage();
        String path = violation.getPropertyPath().toString();
        int idx = path.lastIndexOf('.');
        String field = idx >= 0 ? path.substring(idx + 1) : path;
        return field.isEmpty() || field.startsWith("arg") ? message : field + " " + message;
    }
}
