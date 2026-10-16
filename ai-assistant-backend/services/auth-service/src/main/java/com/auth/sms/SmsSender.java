package com.auth.sms;

/**
 * 短信发送器
 *
 * @author yh
 * @date 2026-10-16
 */
public interface SmsSender {

    /**
     * 发送验证码短信
     *
     * @param phone 手机号
     * @param code 验证码
     */
    void send(String phone, String code);
}
