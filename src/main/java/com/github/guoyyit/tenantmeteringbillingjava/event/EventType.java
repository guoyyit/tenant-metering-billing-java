package com.github.guoyyit.tenantmeteringbillingjava.event;

/**
 * 预写日志事件类型。
 */
public enum EventType {
    TENANT_CONFIGURED,
    RULE_PUT,
    USAGE_ACCEPTED,
    USAGE_REJECTED,
    PERIOD_CLOSED,
    PERIOD_REOPENED,
    BILL_SETTLED
}
