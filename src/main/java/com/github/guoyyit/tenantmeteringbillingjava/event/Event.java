package com.github.guoyyit.tenantmeteringbillingjava.event;

/**
 * 预写日志中的一条事件，序列号全局单调递增。
 * payload 为具体事件 record。
 */
public record Event(
        long seq,
        long timestampEpochMs,
        EventType type,
        Object payload
) {
}
