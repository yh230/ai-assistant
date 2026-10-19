package com.aiassistant.safety;

/**
 * 流式输出命中安全关键词的内部信号（仅用于短路上游流，不对外暴露）
 *
 * @author yh
 * @date 2026-10-19
 */
public class SafetyBlockedException extends RuntimeException {

    public SafetyBlockedException() {
        super("streaming output hit safety keyword");
    }
}
