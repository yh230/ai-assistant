package com.aiassistant.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一接口返回结构。
 * <p>
 * 业务约定：
 * <ul>
 *     <li>200 - 成功</li>
 *     <li>401 - 未登录 / 令牌失效</li>
 *     <li>403 - 已登录但权限不足</li>
 *     <li>400 - 参数校验失败</li>
 *     <li>500 - 业务异常 / 系统异常</li>
 * </ul>
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    /** 响应时间戳（毫秒） */
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMsg("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }

    public static <T> Result<T> fail(String msg) {
        return fail(500, msg);
    }

    public static <T> Result<T> unauthorized(String msg) {
        return fail(401, msg);
    }

    public static <T> Result<T> forbidden(String msg) {
        return fail(403, msg);
    }
}
